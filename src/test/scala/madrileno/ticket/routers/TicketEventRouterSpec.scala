package madrileno.ticket.routers

import madrileno.support.{BaseRouteSpec, TestApplicationLoader, TestData, TicketSeed}
import madrileno.ticket.domain.{Currency, Event, EventId, EventName, EventSection, EventStatus, SeatLabel, SectionName}
import madrileno.ticket.routers.dto.{AddSectionRequest, CreateEventRequest, EventDto, MoneyDto, SectionDto}
import madrileno.utils.http.Error
import madrileno.utils.json.JsonProtocol.*
import org.http4s.Method.*
import org.http4s.Status.*
import pl.iterators.stir.server.Route

import java.time.Instant

class TicketEventRouterSpec extends BaseRouteSpec with TestApplicationLoader with TicketSaleFixtures {

  override def route: Route = application.routes(wsb)

  private val farFuture = Instant.parse("2030-12-01T18:30:00Z")

  path("/v1/events")(
    supports(
      POST,
      description = "Create an event",
      summary = "Creates an OnSale event without sections. Any authenticated user may do so (no organizer roles yet)",
      securitySchemes = Seq(bearerScheme),
      tags = Seq("Tickets")
    )(
      onRequest(body = CreateEventRequest(EventName(" Concert "), farFuture), security = bearer.apply(validJwt(stranger)))
        .respondsWith[EventDto](Created, description = "Event created")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.name shouldBe EventName("Concert")
          response.body.startsAt shouldBe farFuture
          response.body.status shouldBe EventStatus.OnSale
          response.body.sections shouldBe Nil
          val stored = application.transactor.inSession(application.eventRepository.find(response.body.id)).unsafeRunSync()
          stored.map(_.startsAt) shouldBe Some(farFuture)
        },
      // An offset is accepted by the Instant decoder and normalised: 19:30+01:00 is 18:30Z.
      onRequest(body = RawEventRequest("Concert", "2030-12-01T19:30:00+01:00"), security = bearer.apply(validJwt(stranger)))
        .respondsWith[EventDto](Created, description = "Event created from a date with an offset")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.startsAt shouldBe farFuture
          val stored = application.transactor.inSession(application.eventRepository.find(response.body.id)).unsafeRunSync()
          stored.map(_.startsAt) shouldBe Some(farFuture)
        },
      onRequest(body = CreateEventRequest(EventName("Old"), Instant.now().minusSeconds(3600)), security = bearer.apply(validJwt(stranger)))
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Event starts in the past")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Event must start in the future")
        },
      onRequest(body = RawEventRequest("   ", "2030-12-01T18:30:00Z"), security = bearer.apply(validJwt(stranger)))
        .respondsWith[Error[Unit]](BadRequest, description = "Blank name")
        .assert(_.performRequest(allRoutes)),
      onRequest(body = RawEventRequest("Concert", "next friday"), security = bearer.apply(validJwt(stranger)))
        .respondsWith[Error[Unit]](BadRequest, description = "Malformed date")
        .assert(_.performRequest(allRoutes)),
      onRequest(body = RawEventRequest("x" * 201, "2030-12-01T18:30:00Z"), security = bearer.apply(validJwt(stranger)))
        .respondsWith[Error[Unit]](BadRequest, description = "Over-long name")
        .assert(_.performRequest(allRoutes)),
      // A NUL character cannot be stored in Postgres text: it must be a 400, not a 500.
      onRequest(body = RawEventRequest("Con\u0000cert", "2030-12-01T18:30:00Z"), security = bearer.apply(validJwt(stranger)))
        .respondsWith[Error[Unit]](BadRequest, description = "Name with a NUL character")
        .assert(_.performRequest(allRoutes)),
      onRequest(body = CreateEventRequest(EventName("Concert"), farFuture))
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  private def sectionRequest(
    name: String = "Balcony",
    amount: Long = 5_000L,
    labels: List[String] = List("B-1", "B-2")
  ): AddSectionRequest =
    AddSectionRequest(SectionName(name), TestData.ticketPrice(amount), labels.map(SeatLabel(_)))

  private def sectionsOf(eventId: EventId): List[EventSection] =
    application.transactor.inSession(application.eventRepository.findWithSeats(eventId)).unsafeRunSync().toList.flatMap(_.sections)

  path("/v1/events/{eventId}/sections")(
    supports(
      POST,
      description = "Add a section to an event",
      summary = "Adds a priced section with one seat per label. Any authenticated user may do so (no organizer roles yet)",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[EventId]("eventId"),
      tags = Seq("Tickets")
    )(
      withSetup(seedSale())
        .request(sale =>
          onRequest(body = sectionRequest(name = " Balcony "), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId)
        )
        .respondsWith[SectionDto](Created, description = "Section added")
        .assert { case (ctx, sale) =>
          val response = ctx.performRequest(allRoutes)
          response.body.name shouldBe SectionName("Balcony")
          response.body.price shouldBe MoneyDto(5_000L, Currency.PLN)
          response.body.seats.map(_.label) shouldBe List(SeatLabel("B-1"), SeatLabel("B-2"))
          // The returned seat ids are the persisted ones.
          val stored = sectionsOf(sale.event.eventId).find(_.id == response.body.id).getOrElse(fail("section not stored"))
          stored.seats.map(_.id).toSet shouldBe response.body.seats.map(_.id).toSet
        },
      onRequest(body = sectionRequest(), security = bearer.apply(validJwt(stranger)), pathParameters = TestData.randomEventId())
        .respondsWith[Error[Unit]](NotFound, description = "Event not found")
        .assert { ctx =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event not found")
        },
      withSetup(seedSale(startsAt = Instant.now().minusSeconds(60)))
        .request(sale => onRequest(body = sectionRequest(), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId))
        .respondsWith[Error[Unit]](Conflict, description = "Event is not on sale")
        .assert { case (ctx, sale) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event is not on sale")
          sectionsOf(sale.event.eventId).map(_.name) shouldBe List(SectionName("Parter"))
        },
      withSetup(seedSale(status = EventStatus.Cancelled))
        .request(sale => onRequest(body = sectionRequest(), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId))
        .respondsWith[Error[Unit]](Gone, description = "Event has been cancelled")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Event has been cancelled")
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(body = sectionRequest(labels = Nil), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId)
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "No seats in the section")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("A section needs at least one seat")
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = sectionRequest(labels = (1 to Event.MaxSeatsPerSection + 1).toList.map(n => s"S-$n")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Too many seats in the section")
        .assert { case (ctx, sale) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some(s"A section can have at most ${Event.MaxSeatsPerSection} seats")
          sectionsOf(sale.event.eventId).map(_.name) shouldBe List(SectionName("Parter"))
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = sectionRequest(labels = List("B-1", "B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Duplicate seat labels")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Each seat label may be used only once in a section")
        },
      // Zero decodes as a valid Money (only negative amounts fail decoding) and is rejected by the domain.
      withSetup(seedSale())
        .request(sale =>
          onRequest(body = sectionRequest(amount = 0L), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId)
        )
        .respondsWith[Error[Unit]](UnprocessableContent, description = "Price must be positive")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("Section price must be greater than zero")
        },
      // The seeded event already has a section called "Parter".
      withSetup(seedSale())
        .request(sale =>
          onRequest(body = sectionRequest(name = "Parter"), security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId)
        )
        .respondsWith[Error[Unit]](Conflict, description = "Section name already taken")
        .assert { case (ctx, sale) =>
          ctx.performRequest(allRoutes).body.title shouldBe Some("The event already has a section with this name")
          sectionsOf(sale.event.eventId).map(_.name) shouldBe List(SectionName("Parter"))
        },
      // A negative amount fails Money.from while the body is decoded: 400, before the handler runs.
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Balcony", RawPrice(-1L, "PLN"), List("B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Negative price")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("   ", RawPrice(5_000L, "PLN"), List("B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Blank section name")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Balcony", RawPrice(5_000L, "PLN"), List(" ")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Blank seat label")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("x" * 201, RawPrice(5_000L, "PLN"), List("B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Over-long section name")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Bal\u0000cony", RawPrice(5_000L, "PLN"), List("B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Section name with a NUL character")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Balcony", RawPrice(5_000L, "PLN"), List("B-" + "x" * 200)),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Over-long seat label")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Balcony", RawPrice(5_000L, "PLN"), List("B\u00001")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Seat label with a NUL character")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      withSetup(seedSale())
        .request(sale =>
          onRequest(
            body = RawSectionRequest("Balcony", RawPrice(5_000L, "GBP"), List("B-1")),
            security = bearer.apply(validJwt(stranger)),
            pathParameters = sale.event.eventId
          )
        )
        .respondsWith[Error[Unit]](BadRequest, description = "Unknown currency")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes)
        },
      onRequest(body = sectionRequest(), pathParameters = TestData.randomEventId())
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  path("/v1/events/{eventId}")(
    supports(
      GET,
      description = "Get an event by id",
      summary = "Returns the event with its seating plan (sections and seat ids)",
      securitySchemes = Seq(bearerScheme),
      pathParameters = p[EventId]("eventId"),
      tags = Seq("Tickets")
    )(
      withSetup(seedSale())
        .request(sale => onRequest(security = bearer.apply(validJwt(stranger)), pathParameters = sale.event.eventId))
        .respondsWith[EventDto](Ok, description = "Event found")
        .assert { case (ctx, sale) =>
          val response = ctx.performRequest(allRoutes)
          response.body.id shouldBe sale.event.eventId
          response.body.status shouldBe EventStatus.OnSale
          response.body.sections.map(s => (s.name, s.price)) shouldBe List((SectionName("Parter"), MoneyDto(seatPrice.amountMinor, Currency.PLN)))
          response.body.sections.flatMap(_.seats).map(s => (s.id, s.label)).toSet shouldBe sale.event.allSeats.map(s => (s.id, s.label)).toSet
        },
      // An event nobody added a section to has an empty seating plan.
      withSetup(application.transactor.inSession(TicketSeed.event(Nil)).unsafeRunSync())
        .request(event => onRequest(security = bearer.apply(validJwt(stranger)), pathParameters = event.eventId))
        .respondsWith[EventDto](Ok, description = "Event without sections")
        .assert { case (ctx, _) =>
          ctx.performRequest(allRoutes).body.sections shouldBe Nil
        },
      onRequest(security = bearer.apply(validJwt(stranger)), pathParameters = TestData.randomEventId())
        .respondsWith[Error[Unit]](NotFound, description = "Event not found")
        .assert { ctx =>
          val response = ctx.performRequest(allRoutes)
          response.body.title shouldBe Some("Event not found")
        },
      onRequest(pathParameters = TestData.randomEventId())
        .respondsWith[Error[Unit]](Unauthorized, description = "Missing credentials")
        .assert(_.performRequest(allRoutes))
    )
  )

  // Plain values, so a test can send a negative amount, a blank name or an unknown currency.
  private final case class RawPrice(amountMinor: Long, currency: String) derives Encoder.AsObject
  private final case class RawSectionRequest(
    name: String,
    price: RawPrice,
    seatLabels: List[String])
      derives Encoder.AsObject

  // Plain strings, so a test can send a blank name, a malformed date or a date with an offset.
  private final case class RawEventRequest(name: String, startsAt: String) derives Encoder.AsObject
}
