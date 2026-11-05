package madrileno.ticket.routers.dto

import madrileno.ticket.domain.ChargeId
import madrileno.utils.json.JsonProtocol.*

// A blank chargeId fails ChargeId's validation while decoding, so it is rejected with a 400 before the handler runs.
final case class PayTicketRequest(chargeId: ChargeId) derives Decoder, Encoder.AsObject
