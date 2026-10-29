package architecture

import scala.util.Try

final case class Revision(sha: String, root: os.Path)

object GitRevision {
  def withRevision[A](project: os.Path, ref: String)(f: Revision => A): Either[String, A] =
    for {
      _   <- git(project, "rev-parse", "--is-inside-work-tree").left.map(_ => s"$project is not a git repository")
      sha <- git(project, "merge-base", ref, "HEAD")
               .map(_.trim)
               .left
               .map(_ => s"cannot find a merge-base of '$ref' and HEAD (unknown ref or shallow clone?)")
      result <- materialized(project, Revision(sha, os.root), f)
    } yield result

  private def materialized[A](
    project: os.Path,
    revision: Revision,
    f: Revision => A
  ): Either[String, A] = {
    val scratch = os.temp.dir(prefix = "architecture-", deleteOnExit = false)
    try {
      val tree = scratch / "tree"
      os.makeDir.all(tree)
      for {
        listing <- git(project, "ls-tree", "-d", revision.sha, "src/main")
        _       <- if (listing.trim.isEmpty) Right(()) else extract(project, revision.sha, scratch / "base.tar", tree)
      } yield f(revision.copy(root = tree))
    } finally os.remove.all(scratch)
  }

  private def extract(
    project: os.Path,
    sha: String,
    tarball: os.Path,
    tree: os.Path
  ): Either[String, Unit] =
    for {
      _ <- git(project, "archive", "--format=tar", "-o", tarball.toString, sha, "--", "src/main")
      _ <- command(os.proc("tar", "-xf", tarball.toString, "-C", tree.toString)).left.map(error => s"cannot extract $sha: $error")
    } yield ()

  private def git(project: os.Path, args: String*): Either[String, String] =
    command(os.proc(Seq("git", "-C", project.toString) ++ args))

  private def command(proc: os.proc): Either[String, String] =
    Try(proc.call(check = false, stderr = os.Pipe)).toEither.left
      .map(_.getMessage)
      .flatMap(result => if (result.exitCode == 0) Right(result.out.text()) else Left(result.err.text().trim))
}
