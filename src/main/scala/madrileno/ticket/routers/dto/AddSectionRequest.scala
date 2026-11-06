package madrileno.ticket.routers.dto

import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

// The price reuses MoneyDto's wire shape, but is decoded into a Money: a negative amount fails Money.from while
// decoding, so it is rejected with a 400 before the handler runs. Zero decodes fine and is rejected by the domain
// (422 price-must-be-positive). A blank name or label fails SectionName / SeatLabel validation the same way (400).
final case class AddSectionRequest(
  name: SectionName,
  price: Money,
  seatLabels: List[SeatLabel])

object AddSectionRequest {
  private given Decoder[Money] = Decoder[MoneyDto].emap(dto => Money.from(dto.amountMinor, dto.currency))

  given Decoder[AddSectionRequest]          = Decoder.forProduct3("name", "price", "seatLabels")(AddSectionRequest.apply)
  given Encoder.AsObject[AddSectionRequest] =
    Encoder.forProduct3("name", "price", "seatLabels")(request =>
      (request.name, MoneyDto(request.price.amountMinor, request.price.currency), request.seatLabels)
    )
}
