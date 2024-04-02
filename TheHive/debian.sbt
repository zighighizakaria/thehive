import Common.{betaVersion, snapshotVersion, stableVersion, versionUsage}

linuxPackageMappings in Debian += packageMapping(file("LICENSE") -> "/usr/share/doc/thehive/copyright").withPerms("644")
name in Debian := "thehive4"
version in Debian := {
  version.value match {
    case stableVersion(_, _)                      => version.value
    case betaVersion(v1, v2, v3)                  => v1 + "-0." + v3 + "RC" + v2
    case snapshotVersion(stableVersion(v1, v2))   => v1 + "-" + v2 + "-SNAPSHOT"
    case snapshotVersion(betaVersion(v1, v2, v3)) => v1 + "-0." + v3 + "RC" + v2 + "-SNAPSHOT"
    case _                                        => versionUsage(version.value)
  }
}
debianPackageConflicts += "thehive"
debianPackageDependencies += "java8-runtime-headless"
maintainerScripts in Debian := maintainerScriptsFromDirectory(
  baseDirectory.value / "package" / "debian",
  Seq(DebianConstants.Postinst, DebianConstants.Prerm, DebianConstants.Postrm)
)
linuxMakeStartScript in Debian := None
