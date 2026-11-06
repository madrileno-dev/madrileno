# Ticketing

The `ticket` module sells numbered seats for events: a small box office where every section has one price and currency. It lives under `madrileno.ticket` (`domain`, `repositories`, `services`, `routers`) and its schema is `V7__ticket.sql`. This page describes what it does, why it is shaped the way it is, and where it cuts corners on purpose.

## Aggregates and boundaries

```
Event ─┬─ EventSection (name, price) ─── EventSeat (label)        seating plan, read model
       └─ Order (customer) ─── Ticket (one per seat)              one purchase attempt
```

- **Event** owns the seating plan: sections (unique name per event, one price each) and their seats (unique label per section). `Event.addSection` and `Event.holdSeats` are pure functions of the event, the ids and `now` they are given; the domain never reads the clock or generates ids. Whether a seat is still free is *not* part of the event, see [double booking](#double-booking-is-decided-by-the-database).
- **Order** groups the tickets one customer held in one request. It carries no state of its own and has no payment.
- **Ticket** is the unit that is held, paid and cancelled. It snapshots the seat label and the section price at hold time, so later changes to the plan never alter an issued ticket.

Ticket state machine (`Held` -> `Paid` | `Cancelled`; both end states are final):

| From | Transition | To | Rejected when |
| --- | --- | --- | --- |
| `Held` | `pay` (owner, charge id, strictly before `heldUntil`) | `Paid` | not owner, hold expired (`now >= heldUntil`) |
| `Held` | `cancelBy` (owner, user) | `Cancelled` | not owner |
| `Held` | `cancel` (the expiry task / expired-hold release, no owner check) | `Cancelled` | - |
| `Paid` | - | - | `pay`/`cancelBy` answer already paid |
| `Cancelled` | - | - | `pay`/`cancelBy` answer already cancelled |

A Held ticket whose hold ran out is still `Held` in the table until something cancels it; it can be cancelled but not paid.

## Endpoints

All routes are under `/v1` and need `Authorization: Bearer <jwt>`; a missing or invalid token is `401` everywhere. A body that does not decode (bad JSON, a blank, over-long or control-character name/label/charge id, negative price, unknown currency, a seat id that is not a UUID) is `400`. Error bodies follow the envelope in [error-handling.md](error-handling.md); the *error code* column is the stable `type` tag.

| Method and path | Success | Errors (status: error code) |
| --- | --- | --- |
| `POST /events` | `201` event | `422: event-in-the-past`, `422: event-too-far-in-future`, `429: rate-limited` |
| `GET /events/{eventId}` | `200` event with its seating plan (`sections` is empty for a new event) | `404: event-not-found` |
| `POST /events/{eventId}/sections` | `201` section with seat ids | `404: event-not-found`, `409: event-not-on-sale`, `409: section-name-taken`, `410: event-cancelled`, `422: no-seats-in-section`, `422: too-many-seats-in-section`, `422: duplicate-seat-labels`, `422: price-must-be-positive`, `429: rate-limited` |
| `POST /events/{eventId}/orders` (hold seats) | `201` order with its Held tickets | `404: event-not-found`, `409: event-not-on-sale`, `409: seat-already-taken`, `410: event-cancelled`, `422: no-seats-selected`, `422: too-many-seats`, `422: duplicate-seats`, `422: seat-not-in-event`, `429: rate-limited` |
| `GET /tickets/{ticketId}` | `200` ticket | `404: ticket-not-found` |
| `POST /tickets/{ticketId}/pay` | `200` ticket | `404: ticket-not-found`, `409: ticket-already-paid`, `409: charge-already-used`, `410: ticket-cancelled`, `410: ticket-expired` |
| `POST /tickets/{ticketId}/cancel` | `200` ticket | `404: ticket-not-found`, `409: ticket-already-paid`, `410: ticket-cancelled` |

### Rate limits

The three write endpoints that create rows are limited per user (the key is the authenticated user id, not the IP), using the same `rateLimited` directive as the auction module (see [rate-limiting.md](rate-limiting.md)). Over the limit the answer is `429 rate-limited` with a `Retry-After` header.

| Endpoint | Limit | Bucket | Why this number |
| --- | --- | --- | --- |
| `POST /events` | 10 per minute | `events.create` | the same as `auctions.create`: creating an aggregate is rare for a real user |
| `POST /events/{eventId}/sections` | 10 per minute | `events.add-section` | also creates rows (up to 500 seats) and takes the event lock, so it follows the create limit |
| `POST /events/{eventId}/orders` (hold) | 30 per minute | `events.hold` | the same as `auctions.bid`: a frequent, contended action, but every call locks seats and writes tickets |

Reads and the pay/cancel endpoints are not limited. The specs run with an unbounded limiter (`TestRateLimiterRuntime.unbounded`) and, as for the auction module, there is no 429 scenario in the ticket router specs.

The status split follows one rule: `409` is a state that could still change (sales not open yet, a retry racing the first payment), `410` is permanent (a cancelled event or ticket will never come back), `422` is a request that is well-formed but breaks a business rule, `404` is also used for anything that belongs to somebody else.

## Key decisions

### Double booking is decided by the database

`ticket_live_seat_uidx` is a partial unique index on `ticket(seat_id) WHERE status IN ('Held','Paid')`. Availability is never checked beforehand (check-then-act would race); the `INSERT` is the check, and the loser of a race gets a unique violation. `TicketService.holdSeats` translates exactly that violation (SQLSTATE `23505` on that index name) into `409 seat-already-taken`, and it does so *outside* `transactor.inTransaction`: a failed statement aborts the Postgres transaction, so nothing can be done inside it, and by the time the handler runs the whole order has been rolled back. Any other constraint or error stays an exception (a `500`). `ticket_charge_id_uidx` is handled the same way for `charge-already-used`.

### One mutation path, with a row lock

`TicketRepository.update(id, f)` is the only way an existing ticket changes: it reads the row `FOR UPDATE`, applies the domain transition `f` and writes the result back; on `Left` nothing is written. Pay, user cancel, the expiry task and the expired-hold release all go through it, so two concurrent transitions of one ticket serialise and the second one sees the first one's result. `EventRepository.addSection` follows the same shape with the event row as the lock.

### Ownership lives on the aggregate; foreign tickets are 404

`Ticket.pay` and `Ticket.cancelBy` take the requester and check ownership *first*, before state and time, so a stranger learns nothing about a ticket's status. The service maps `NotOwner` to the same result as a missing ticket (`404 ticket-not-found`): the API cannot be used to probe which ids exist. `Ticket.cancel(now)` has no requester on purpose; it is for the system (expiry task) only.

### Hold duration and expiry

A hold lasts `ticket.hold-duration` (default `10 minutes`, env `TICKET_HOLD_DURATION`, must be > 0). `heldUntil = now + duration` is computed once and stored on the ticket, so changing the setting never affects existing holds. A Held row keeps its seat blocked in the unique index until it is cancelled, so expired holds are released in two places:

- **on re-hold**: `holdSeats` cancels the expired holds on the requested seats in the same transaction, before inserting the new tickets, so a seat with a dead hold is never reported as taken;
- **by the task** `cancel-expired-ticket-holds`, once a minute, which cleans up expired holds on seats nobody asks for. Each ticket is cancelled in its own transaction and re-checked under the row lock, so a hold paid in the meantime is skipped.

### Canonical insert order against deadlocks

`holdSeats` inserts the tickets of an order sorted by seat id, not in request order (the response keeps the request order). A unique-index insert waits for a concurrent transaction that inserted the same seat; two holds of `[A,B]` and `[B,A]` in request order would wait for each other and Postgres would kill one with a deadlock (`40P01`, a `500`). With one global order, one transaction wins the lowest contested seat and the other fails cleanly with `seat-already-taken`. The release of expired holds (`releaseExpiredHolds`) follows the same idea: the old, expired tickets of the requested seats are cancelled in ticket-id order, and that happens before the new tickets are inserted in seat-id order. Two ticket orders are involved, so it is not one single order: each phase is ordered on its own, which is enough because the phases always run in that sequence.

### At most 8 seats per order

`Event.MaxSeatsPerOrder = 8` stops one customer from blocking a large part of the hall with a single request. It is a product decision, not a technical one; the check is in `Event.holdSeats` and answers `422 too-many-seats`.

### Adding sections

Seats are created through `POST /events/{eventId}/sections`. The repository locks the event row `FOR NO KEY UPDATE` (not `FOR UPDATE`: that would conflict with the `FOR KEY SHARE` lock Postgres takes on the event row for the foreign key of `ticket_order.event_id`, and block every concurrent hold), loads the plan, applies `Event.addSection` and inserts the section and its seats; a domain rejection writes nothing. The lock serialises additions to one event, which is what makes the name check in the domain race-free. The unique constraints on `(event_id, name)` and `(section_id, label)` remain as a backstop and are deliberately not translated: a violation would be a bug. Sections can only be added while the event is on sale, so the plan is frozen once sales stop. A section price must be greater than zero (the column `CHECK` requires it; `Money` itself accepts `0`).

### Input limits

Bounds that keep oversized or odd input from turning into a `500`:

| Limit | Value | Where | Answer |
| --- | --- | --- | --- |
| seats per section | 500 (`Event.MaxSeatsPerSection`) | the seat `INSERT` binds 3 parameters per seat and Postgres allows 65,535; the 10 MiB body limit alone would allow more | `422 too-many-seats-in-section` |
| seats per order | 8 (`Event.MaxSeatsPerOrder`) | product decision, see above | `422 too-many-seats` |
| event name, section name, seat label, charge id | at most 200 characters after trimming, no control characters (a NUL cannot be stored by Postgres text) | `TextRules`, mirrored by `CHECK (char_length(..) <= 200)` in V7 | `400` (the body does not decode) |
| event start | at most 5 years (5 * 365 days) ahead, `Event.MaxEventHorizon` | a year like `+999999999` is a valid `Instant` but cannot be stored | `422 event-too-far-in-future` |

String length is counted in code points, like Postgres `char_length`. Control characters at the very edge of a value are removed by the trim; only the ones inside are rejected.

## Known simplifications and debt

- **No organizer roles.** Any logged-in user can create events and add sections to any event. Marked in the service methods.
- **No outbox.** An order and its tickets are written in one transaction, and no domain event is published for other modules. Accepted for now.
- **No payment gateway.** The client supplies `chargeId` (an opaque provider reference); the module only guarantees that one charge pays at most one ticket. Nothing verifies the charge.
- **Free tickets are unsupported.** `price_minor > 0` is a database `CHECK`; allowing `0` means changing the domain and the constraints together.
- **Payment is per ticket.** There is no order-level payment and no refunds, so a paid ticket cannot be cancelled (`409 ticket-already-paid`).
- **No way to edit or remove a section or seat** once created (the size of one section is capped, see [input limits](#input-limits)).
- **No reaction to account deletion.** The module does not subscribe to `UserAccountDeleted`: the Held tickets of a deleted user simply expire, and Paid tickets keep pointing at the user.
- **No way to read an order or list one's tickets.** Only `GET /tickets/{ticketId}` exists, so a client that loses the response of the hold request cannot recover its ticket ids.

## Try it

You need Docker, sbt and `curl`; `jq` makes the copy-paste easier. Run the sbt commands from the project root; see the root [`README.md`](../README.md) for the one-time setup (`.env` from `.env.sample`, which enables dev login).

```bash
docker compose up -d
sbt "runMain madrileno.main.MigrateMain"
sbt --client reStart                       # or ~reStart in a separate terminal
curl -sf http://localhost:9000/v1/health-check
```

Log in (dev login creates the user on first call and answers `{ "jwt", "refreshToken", "userCreated" }`):

```bash
TOKEN=$(curl -s -X POST localhost:9000/v1/auth/dev \
  -H 'Content-Type: application/json' -d '{"email":"alice@example.com"}' | jq -r .jwt)
AUTH="Authorization: Bearer $TOKEN"
```

Create an event, add a section and read the seat ids back (amounts are in minor units, here 120.00 PLN):

```bash
EVENT=$(curl -s -X POST localhost:9000/v1/events -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Concert","startsAt":"2030-12-01T18:30:00Z"}' | jq -r .id)

curl -s -X POST localhost:9000/v1/events/$EVENT/sections -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Parter","price":{"amountMinor":12000,"currency":"PLN"},"seatLabels":["A-1","A-2","A-3"]}' | jq

SEAT=$(curl -s localhost:9000/v1/events/$EVENT -H "$AUTH" | jq -r '.sections[0].seats[0].id')
```

Hold the seat, then pay for the ticket (or `POST .../cancel` to give it up):

```bash
TICKET=$(curl -s -X POST localhost:9000/v1/events/$EVENT/orders -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"seatIds\":[\"$SEAT\"]}" | jq -r '.tickets[0].id')

curl -s -X POST localhost:9000/v1/tickets/$TICKET/pay -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"chargeId":"ch_demo_1"}' | jq
```

Holding the same seat again now answers `409 seat-already-taken`; paying twice answers `409 ticket-already-paid`. To use Swagger UI instead, open `http://localhost:9000/swagger` (dev only; it needs the spec generated by `sbt testFull`, see [http.md](http.md)), log in with `POST /v1/auth/dev` there and paste the `jwt` into Authorize. Stop everything with `sbt --client reStop` and `docker compose stop`.
