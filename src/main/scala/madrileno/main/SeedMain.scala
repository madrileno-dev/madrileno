package madrileno.main

import cats.effect.{Clock, IO, IOApp}
import cats.syntax.all.*
import madrileno.auth.domain.*
import madrileno.auth.repositories.UserAuthRepository
import madrileno.user.domain.*
import madrileno.user.repositories.UserRepository
import madrileno.utils.db.transactor.{DBInTransaction, PgConfig, PgTransactor, Transactor}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

object SeedMain extends IOApp.Simple {

  override def run: IO[Unit] = {
    given MeterProvider[IO]  = MeterProvider.noop[IO]
    given TracerProvider[IO] = TracerProvider.noop[IO]

    for {
      config    <- IO.delay(ConfigSource.default)
      appConfig <- IO.delay(config.at("app").loadOrThrow[AppConfig])
      pgConfig  <- IO.delay(config.at("pg").loadOrThrow[PgConfig])
      _         <- IO.raiseUnless(appConfig.environment == Environment.Dev)(
             new IllegalStateException(s"SeedMain refuses to run with app.environment=${appConfig.environment}. Dev only.")
           )
      _ <- PgTransactor.resource(pgConfig).use { transactor =>
             seed(transactor, new UserRepository, new UserAuthRepository)
           }
    } yield ()
  }

  private val demoUsers: List[DemoUser] = List(
    DemoUser(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Demo User", "demo@example.com"),
    DemoUser(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alice Admin", "alice@example.com"),
    DemoUser(UUID.fromString("00000000-0000-0000-0000-000000000003"), "Bob Builder", "bob@example.com")
  )

  private def seed(
    transactor: Transactor,
    userRepository: UserRepository,
    userAuthRepository: UserAuthRepository
  ): IO[Unit] = {
    for {
      now     <- Clock[IO].realTimeInstant
      results <- transactor.inTransaction(demoUsers.traverse(seedDemoUser(userRepository, userAuthRepository, now)))
      _       <- IO.println(s"Seeded: ${results.count(identity)} new, ${results.count(!_)} already present.")
      _       <- IO.println("Log in as any of them via: POST /v1/auth/dev with body {\"email\":\"<email>\"}")
    } yield ()
  }

  private def seedDemoUser(
    userRepository: UserRepository,
    userAuthRepository: UserAuthRepository,
    now: Instant
  )(
    d: DemoUser
  ): DBInTransaction[Boolean] = {
    val userId         = UserId(d.id)
    val userAuthId     = UserAuthId(deriveUuid(d.id, "user-auth"))
    val email          = EmailAddress(d.email)
    val providerUserId = ProviderUserId(email.unwrap)
    val user           =
      User(id = userId, fullName = Some(FullName(d.fullName)), emailAddress = Some(email), emailVerified = true, avatarUrl = None, blockedAt = None)
    val userAuth = UserAuth(
      id = userAuthId,
      userId = userId,
      provider = Provider.Dev,
      providerUserId = providerUserId,
      credential = Credential(email.unwrap),
      metadata = Metadata.empty
    )
    for {
      userCreated <- findOrCreate(userRepository.find(userId))(userRepository.create(user, now))
      _           <- userAuthRepository.findForUpdate(Provider.Dev, providerUserId).flatMap {
             case Some(existing) if existing.userId == userId => IO.unit
             case Some(existing)                              =>
               userAuthRepository.softDelete(existing.id, now) *> userAuthRepository.save(userAuth, now).void
             case None => userAuthRepository.save(userAuth, now).void
           }
    } yield userCreated
  }

  private def findOrCreate[A, B](find: DBInTransaction[Option[A]])(create: => DBInTransaction[B]): DBInTransaction[Boolean] =
    find.flatMap {
      case Some(_) => IO.pure(false)
      case None    => create.as(true)
    }

  private final case class DemoUser(
    id: UUID,
    fullName: String,
    email: String)

  private def deriveUuid(parent: UUID, kind: String): UUID =
    UUID.nameUUIDFromBytes(s"$parent:$kind".getBytes(UTF_8))
}
