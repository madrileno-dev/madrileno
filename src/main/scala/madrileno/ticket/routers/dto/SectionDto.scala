package madrileno.ticket.routers.dto

import io.scalaland.chimney.dsl.*
import madrileno.ticket.domain.*
import madrileno.utils.json.JsonProtocol.*

final case class SeatDto(id: SeatId, label: SeatLabel) derives Encoder.AsObject, Decoder

final case class SectionDto(
  id: SectionId,
  name: SectionName,
  price: MoneyDto,
  seats: List[SeatDto])
    derives Encoder.AsObject,
      Decoder

object SectionDto {
  def apply(section: EventSection): SectionDto = section.into[SectionDto].transform
}
