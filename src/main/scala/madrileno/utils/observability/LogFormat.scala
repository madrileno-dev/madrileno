package madrileno.utils.observability

import pureconfig.generic.derivation.EnumConfigReader

enum LogFormat derives EnumConfigReader {
  case Text, Json
}
