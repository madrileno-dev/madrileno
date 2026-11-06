package __package__.__aggregate__.routers.dto

import io.scalaland.chimney.dsl.*
import __package__.__aggregate__.domain.{__Aggregate__, __Aggregate__Id, __Aggregate__Name}
import __package__.utils.json.JsonProtocol.*

final case class __Aggregate__Dto(id: __Aggregate__Id, name: __Aggregate__Name) derives Encoder.AsObject, Decoder

object __Aggregate__Dto {
  def apply(__aggregate__: __Aggregate__): __Aggregate__Dto = __aggregate__.into[__Aggregate__Dto].transform
}
