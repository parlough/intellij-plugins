import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.qodana
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2022.10"

project {
  vcsRoot(ProjectVcsRoot)
  buildType(Build)
}

object Build : BuildType({
  name = "Build"
  vcs {
    root(ProjectVcsRoot)
  }
  steps {
    qodana {
      name = "Qodana Step"
      reportAsTests = true
      linter = <linter> {}
    }
  }
  triggers {
    vcs {}
  }
  features {
    perfmon {}
  }
})

object ProjectVcsRoot : GitVcsRoot({
  name = "null"
  url = "null"
  branch = "refs/heads/main"
  branchSpec = """

  """.trimIndent()
})