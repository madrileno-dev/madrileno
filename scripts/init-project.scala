#!/usr/bin/env -S scala-cli shebang

//> using scala 3.9.0
//> using jvm 21
//> using toolkit default
//> using dep com.lihaoyi::mainargs:0.7.8

// Rename this project from `madrileno` to `<name>`, swap the package, and delete
// the auction demo. Run from the project root, either directly:
//
//   ./scripts/init-project.scala wine-cellar
//   ./scripts/init-project.scala wine-cellar --package winecellar
//
// or via scala-cli:
//
//   scala-cli run scripts/init-project.scala -- wine-cellar
//
// After: `sbt compile`. If anything's off, `git checkout .` reverts.

import mainargs.{Flag, ParserForMethods, arg, main}

import java.util.regex.Matcher

object InitProject {

  // Directories we never descend into. Build caches, IDE state, logs, this scripts dir.
  private val SkipDirs: Set[String] =
    Set(".git", "target", ".bsp", ".bloop", ".scala-build", ".metals", ".idea", ".vscode", "logs", "node_modules", "scripts")

  // Files we never read as text (would corrupt or fail).
  private val BinaryExts: Set[String] =
    Set("jpg", "jpeg", "png", "gif", "ico", "jar", "class", "tasty", "woff", "woff2", "ttf", "otf", "pdf", "zip", "tar", "gz", "bin")

  private val MadrilenoUpstream = "https://github.com/luksow/madrileno.git"

  @main
  def run(
    @arg(positional = true, doc = "Project name (e.g. 'wine-cellar')")
    name: String,
    @arg(name = "package", doc = "Scala package; defaults to project name lowercased with non-alphanumerics stripped")
    packageOpt: Option[String] = None,
    @arg(name = "keep-docs", doc = "Keep the `docs/` tree (default: deleted — MCP server serves them from the pinned ref)")
    keepDocs: Flag = Flag()
  ): Unit = {

    require(name.matches("[A-Za-z][A-Za-z0-9_-]*"),
      s"project name '$name' must start with a letter and contain only letters, digits, '-' and '_' (sbt + HOCON + docker-compose all share that bar)")
    val packageName = packageOpt.getOrElse(name.toLowerCase.replaceAll("[^a-z0-9]", ""))
    require(packageName.matches("[a-z][a-z0-9]*"),
      s"package '$packageName' is not a lowercase Scala identifier (derived from '$name'; pass --package explicitly)")

    val root = os.pwd
    require(os.exists(root / "build.sbt"),
      s"$root doesn't look like an sbt project (no build.sbt)")
    require(os.exists(root / "src" / "main" / "scala" / "madrileno"),
      "no src/main/scala/madrileno tree found — already renamed?")

    // Read the upstream sha + the local clone's `origin` URL before doing any destructive work.
    // The MCP server hard-requires `.madrileno-ref` to point at a real commit reachable from
    // some git remote; if we can't resolve either here, fail now rather than mid-init.
    val shaProc = os.proc("git", "-C", root.toString, "rev-parse", "HEAD").call(check = false)
    val sha     = shaProc.out.text().trim
    require(shaProc.exitCode == 0 && sha.nonEmpty,
      s"can't resolve upstream sha (`git -C $root rev-parse HEAD` failed). " +
        "init-project must be run from a git clone of madrileno — that's how the MCP anchor is pinned. " +
        "If you don't have git, you can skip init-project entirely (the rename/auction-delete is optional) and write `.madrileno-ref` by hand for the MCP server (see docs/mcp.md).")

    // Derive the upstream URL from `git remote get-url origin`, so users who cloned from a fork
    // (or via SSH from upstream) get a `.madrileno-ref` that actually resolves the sha. Fall back
    // to the hardcoded URL if there's no `origin` remote.
    val originProc   = os.proc("git", "-C", root.toString, "remote", "get-url", "origin").call(check = false)
    val originUrl    = originProc.out.text().trim
    val upstreamRepo = if (originProc.exitCode == 0 && originUrl.nonEmpty) originUrl else MadrilenoUpstream

    // 1. Delete the auction demo: source, tests, related Flyway migrations.
    val auctionDirs = for {
      kind    <- Seq("main", "test")
      auction = root / "src" / kind / "scala" / "madrileno" / "auction"
      if os.exists(auction)
    } yield auction
    val migrations = root / "src" / "main" / "resources" / "db" / "migration"
    val auctionMigrations =
      if (os.exists(migrations))
        os.list(migrations).filter(p => p.last.toLowerCase.contains("auction") || p.last.toLowerCase.contains("bid")).toList
      else List.empty
    val deleted = (auctionDirs ++ auctionMigrations).toList
    auctionDirs.foreach(os.remove.all)
    auctionMigrations.foreach(os.remove)

    // 1b. Template-internal CI helpers — drop on init. They validate template content
    //     (link integrity in our docs, scripts/*.scala compile-cleanliness) and don't carry
    //     value past the rename. `check-links.scala` is also template-only — after init the
    //     `docs/` tree is typically gone (served by the MCP from the pinned ref) so the
    //     link graph it walks no longer exists locally.
    val templateCiFiles = List(
      root / ".github" / "workflows" / "link-check.yml",
      root / ".github" / "workflows" / "script-tests.yml",
      root / "scripts" / "check-links.scala"
    ).filter(os.exists)
    templateCiFiles.foreach(os.remove)

    val licenseFile    = root / "LICENSE"
    val licenseDeleted = os.exists(licenseFile)
    if (licenseDeleted) os.remove(licenseFile)
    val readme = root / "README.md"
    if (os.exists(readme)) {
      val current  = os.read(readme)
      val stripped = """(?ms)^## License[ \t]*\r?\n.*?(?=^## |\z)""".r.replaceAllIn(current, "")
      if (stripped != current) os.write.over(readme, stripped)
    }

    // 2. Rewrite text files. Order matters: do the auction surgery while the strings
    //    still say `madrileno.auction`, then run the package + standalone-name renames.
    //    Auction surgery:
    //      a) drop `import madrileno.auction.<X>` lines
    //      b) drop `with AuctionModule` lines from the loaders' extends chains
    //      c) drop blocks bracketed by `// scripts:auction-block-start` / `-end`
    //         (used in test support — TestData, TestApplicationLoader)
    // Markers must occupy their own line (with optional indentation). Anchoring keeps
    // the regex from eating prose in docs that mentions the marker strings inline.
    val auctionBlock = """(?ms)^[ \t]*// scripts:auction-block-start[ \t]*\r?\n.*?^[ \t]*// scripts:auction-block-end[ \t]*\r?\n?""".r
    // docs/mcp.md documents the MCP system itself — its `madrileno` references (tool names, the
    // upstream repo URL in the `.madrileno-ref` example, package paths in the example query)
    // are intentional and must not be renamed. Skip the file from the rewrite pass.
    val mcpDoc = root / "docs" / "mcp.md"
    val touched = os.walk(root)
      .filter(p => os.isFile(p, followLinks = false))
      .filter(p => !p.relativeTo(root).segments.exists(SkipDirs.contains))
      .filter(p => !BinaryExts.contains(p.ext.toLowerCase))
      .filter(p => p != mcpDoc)
      .flatMap { p =>
        val original = os.read(p)
        val transformed = auctionBlock
          .replaceAllIn(original, "")
          .replaceAll("""(?m)^import madrileno\.auction\..*\r?\n""", "")
          .replaceAll("""(?m)^[ \t]*with AuctionModule[ \t]*\r?\n""", "")
          .replace("madrileno.", s"$packageName.")
          .replaceAll("""\bmadrileno\b""", Matcher.quoteReplacement(name))
        if (transformed != original) {
          os.write.over(p, transformed)
          Some(p)
        } else None
      }
      .toList

    // 3. Rename the source directories.
    for {
      kind <- Seq("main", "test")
      old  = root / "src" / kind / "scala" / "madrileno"
      if os.exists(old)
    } {
      os.move(old, root / "src" / kind / "scala" / packageName)
    }

    // 4. Pin the upstream madrileno ref so the MCP server knows which commit to anchor to.
    //    `sha` and `upstreamRepo` were resolved + validated at the top of `run`.
    os.write.over(root / ".madrileno-ref", s"repo=$upstreamRepo\nref=$sha\n")

    // 5. .gitignore: whitelist `.madrileno-ref` (the template's `.*` rule would otherwise hide it),
    //    and add `.madrileno-mcp/` (the shadow clone the MCP server keeps for serving docs/source).
    val gitignore = root / ".gitignore"
    if (os.exists(gitignore)) {
      val current   = os.read(gitignore)
      val needsRef  = !current.linesIterator.exists(_.trim == "!.madrileno-ref")
      val needsMcp  = !current.linesIterator.exists(_.trim == ".madrileno-mcp/")
      if (needsRef || needsMcp) {
        val addRef = if (needsRef) "!.madrileno-ref\n" else ""
        val addMcp = if (needsMcp) "\n# MCP server shadow clone\n.madrileno-mcp/\n" else ""
        os.write.over(gitignore, current + (if (current.endsWith("\n")) "" else "\n") + addRef + addMcp)
      }
    }

    // 6. Delete the docs unless --keep-docs. The MCP server serves them on demand from
    //    the pinned ref, so most projects don't need to carry them locally.
    val docsRoot     = root / "docs"
    val docsDeleted  = !keepDocs.value && os.exists(docsRoot)
    if (docsDeleted) {
      os.remove.all(docsRoot)
    }

    // 7. If docs were deleted, rewrite the surviving `docs/<X>.md` link targets in README.md
    //    (and anywhere else that linked into docs) to point at upstream at the pinned sha,
    //    so onboarding links remain clickable. Use the *derived* origin URL if it's a github
    //    https URL — otherwise (SSH/local/other) the github blob path doesn't apply, and we
    //    fall back to the canonical upstream (links land on luksow/madrileno, which may not
    //    contain a fork-only sha but is the best we can do without scheme coercion).
    val upstreamWeb =
      if (upstreamRepo.startsWith("https://github.com/")) upstreamRepo.stripSuffix(".git")
      else MadrilenoUpstream.stripSuffix(".git")
    val docLinkTarget = """\]\(docs/([^)]+)\)""".r
    val linkRewrites: List[os.Path] =
      if (docsDeleted) {
        val docLinkBase = s"$upstreamWeb/blob/$sha/docs"
        os.walk(root)
          .filter(p => os.isFile(p, followLinks = false))
          .filter(p => !p.relativeTo(root).segments.exists(SkipDirs.contains))
          .filter(p => p.ext.toLowerCase == "md")
          .flatMap { p =>
            val original  = os.read(p)
            val rewritten = docLinkTarget.replaceAllIn(original, m => s"](${docLinkBase}/${Matcher.quoteReplacement(m.group(1))})")
            if (rewritten != original) {
              os.write.over(p, rewritten)
              Some(p)
            } else None
          }
          .toList
      } else List.empty

    val totalUpdated = (touched.toSet ++ linkRewrites.toSet).size
    println(s"Project: $name")
    println(s"Package: $packageName")
    println(s"Deleted: ${deleted.size} auction-related paths")
    if (templateCiFiles.nonEmpty) println(s"Deleted: ${templateCiFiles.size} template-internal CI files (link-check + script-tests workflows, check-links.scala)")
    if (licenseDeleted) println("Deleted: LICENSE (the template's Apache-2.0 — generated projects may relicense freely)")
    if (docsDeleted) println("Deleted: docs/ (pass --keep-docs to retain a local copy; the MCP server serves them from the pinned ref)")
    println(s"Updated: $totalUpdated files")
    println(s"Anchored: $upstreamRepo @ ${sha.take(10)} (see .madrileno-ref)")
    println()

    // Auction surgery leaves orphaned imports (e.g., `utils.imaging.*`, `utils.storage.StorageKey`,
    // `org.http4s.MediaType`) in shared files like TestData.scala. scalafix's RemoveUnused +
    // OrganizeImports.removeUnused clears them. Two passes because removing one import can
    // make another unused (cascading). One-time cost on init; bundling it here means the user
    // gets a green `sbt testFull` on the very next command.
    println("Running scalafix to clean up auction-leftover imports (this takes a minute on first run)...")
    val scalafix = os.proc("sbt", "scalafixAll", "scalafixAll").call(cwd = root, check = false, stdout = os.Inherit, stderr = os.Inherit)
    if (scalafix.exitCode != 0) {
      println()
      println("⚠ scalafix didn't complete cleanly. The rename + auction deletion still applied; you can re-run scalafix manually:")
      println("  sbt 'scalafixAll; scalafixAll'")
    }

    println()
    println("Next:")
    println("  cp .env.sample .env")
    println("  sbt testFull   # full, uncached run — sbt 2's `test` is incremental/cached")
    if (licenseDeleted) println("  pick a LICENSE for your project        # the template's Apache-2.0 file was removed")
    val mcpDocLink =
      if (docsDeleted) s"$upstreamWeb/blob/$sha/docs/mcp.md"
      else "docs/mcp.md"
    println(s"  ./scripts/mcp-server.scala &           # optional — start the docs/source MCP for Claude (see $mcpDocLink)")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
