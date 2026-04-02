# Architecture

## FINOS CALM architecture diagram

FINOS CALM (Common Architecture Language Model) architecture diagram showing services, databases, external integrations, and messaging connections. Use this to understand the high-level system architecture and component relationships.

## Data Tables

### Project metadata

**File:** [`project-metadata.csv`](project-metadata.csv)

Project-level identity and structure for each build module. Includes Maven GAV coordinates, display name, description, parent project lineage, and submodule count. Use this to understand what the project is, how it relates to parent projects, and whether it is a multi-module aggregator.

| Column | Description |
|--------|-------------|
| Source path | The path to the build file (pom.xml or build.gradle). |
| Artifact ID | The project's artifact ID (Maven) or project name (Gradle). |
| Group ID | The project's group ID. |
| Name | The project's display name. |
| Description | The project's description. |
| Version | The project's version. |
| Parent project | The parent project coordinates (e.g., groupId:artifactId:version for Maven). |
| Module count | The number of declared submodules for aggregator projects. |

