package madrileno.sourcechecks

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}

class AgentInstructionsSpec extends AnyWordSpec with Matchers {
  import AgentInstructions.*

  "AgentInstructions" should {
    "split a skill into its frontmatter and body" in {
      split("---\nname: backend-x\ndescription: Does x: and y\n---\n# X\nsteps") shouldBe
        (Map("name" -> "backend-x", "description" -> "Does x: and y"), "# X\nsteps")
    }

    "find the docs and skills a text names" in {
      docsNamed("see doc `database` and doc `http`, not `docs/x.md`") shouldBe Set("database", "http")
      skillsNamed("use `backend-add-module` or `backend-add-endpoint`") shouldBe Set("backend-add-module", "backend-add-endpoint")
    }
  }

  "The skills" should {
    "be named after their folder, with the backend- prefix" in {
      skills should not be empty
      skills.filterNot(skill => skill.frontmatter.get("name").contains(skill.folder)).map(_.folder) shouldBe empty
      all(skills.map(_.folder)) should fullyMatch regex "backend-[a-z0-9-]{1,56}"
    }

    "carry only a name and a description, so Claude Code and Codex read them alike" in {
      all(skills.map(_.frontmatter.keySet)) shouldBe Set("name", "description")
      all(skills.map(_.frontmatter("description").length)) should (be > 0 and be <= 1024)
    }

    "name only docs that exist" in {
      assume(Files.isDirectory(docsRoot), "docs/ is not in this project; the MCP server serves it")
      namedDocs.filterNot(name => Files.exists(docsRoot.resolve(s"$name.md"))) shouldBe empty
    }

    "name only docs short enough to reach an agent whole" in {
      assume(Files.isDirectory(docsRoot), "docs/ is not in this project; the MCP server serves it")
      namedDocs.filter(name => Files.size(docsRoot.resolve(s"$name.md")) > 25_000) shouldBe empty
    }

    "be exactly the ones AGENTS.md lists" in {
      skillsNamed(Files.readString(agentsFile)) shouldBe skills.map(_.folder).toSet
    }

    "reach Claude Code through a .claude/skills link to .agents/skills" in {
      Files.isSymbolicLink(claudeSkills) shouldBe true
      Files.readSymbolicLink(claudeSkills) shouldBe Paths.get("../.agents/skills")
    }
  }

  private def namedDocs: Set[String] = (skills.map(_.body) :+ Files.readString(agentsFile)).flatMap(docsNamed).toSet

  "The workspace description" should {
    "give the backend a role and a summary" in {
      val (frontmatter, body) = split(Files.readString(workspaceFile))
      frontmatter.get("role") shouldBe Some("backend")
      frontmatter.get("summary") should not be empty
      body should include("{dir}")
    }
  }
}
