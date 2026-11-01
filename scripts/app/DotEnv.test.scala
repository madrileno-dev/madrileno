package app

class DotEnvSuite extends munit.FunSuite {
  test("ignores blank lines and comments") {
    assertEquals(DotEnv.parse("\n# app\n\n   \n"), Map.empty[String, String])
  }

  test("reads a bare value") {
    assertEquals(DotEnv.parse("PORT=9000"), Map("PORT" -> "9000"))
  }

  test("strips single and double quotes") {
    assertEquals(
      DotEnv.parse("PG_HOST='localhost'\nBASE_URL=\"http://localhost:9000\""),
      Map("PG_HOST" -> "localhost", "BASE_URL" -> "http://localhost:9000")
    )
  }

  test("keeps an equals sign inside the value") {
    assertEquals(
      DotEnv.parse("OTEL_EXPORTER_OTLP_HEADERS=\"authorization=Basic abc=\""),
      Map("OTEL_EXPORTER_OTLP_HEADERS" -> "authorization=Basic abc=")
    )
  }

  test("reads empty values, quoted or not") {
    assertEquals(DotEnv.parse("FIREBASE_PROJECT_ID=''\nCORS_ALLOWED_ORIGINS="), Map("FIREBASE_PROJECT_ID" -> "", "CORS_ALLOWED_ORIGINS" -> ""))
  }

  test("accepts an export prefix") {
    assertEquals(DotEnv.parse("export JWT_SECRET='secret'"), Map("JWT_SECRET" -> "secret"))
  }

  test("drops an inline comment after an unquoted value, but not a hash inside quotes") {
    assertEquals(DotEnv.parse("A=value # note\nB='Complexpass#123'"), Map("A" -> "value", "B" -> "Complexpass#123"))
  }

  test("the last assignment of a key wins") {
    assertEquals(DotEnv.parse("PORT=9000\nPORT=9100"), Map("PORT" -> "9100"))
  }
}
