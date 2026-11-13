package madrileno.healthcheck.services

import scala.concurrent.duration.*

final case class ReadinessConfig(interval: FiniteDuration = 2.seconds) {
  val validFor: FiniteDuration = interval * 3
}
