Install & Run: https://typedb.com/docs/home/install

Download from TypeDB Package Repository: 

Server only: [Distributions for 2.26.6](https://cloudsmith.io/~typedb/repos/public-release/packages/?q=name:^typedb-server+version:2.26.6)

Server + Console: [Distributions for 2.26.6](https://cloudsmith.io/~typedb/repos/public-release/packages/?q=name:^typedb-all+version:2.26.6)


## New Features
- **Enable Unicode variable names**
  
  We update to TypeQL with Unicode support in both value and concept variables. This makes the following valid TypeQL:
  
  ```
  match $人 isa person, has name "Liu"; get  $人;
  ```
  
  ```
  match $אדם isa person, has name "Solomon"; get $אדם;
  ```
  
  This change is fully backwards compatible.
  
  

## Bugs Fixed
- **Fix schema export of overridden plays edge**
  Make schema exports consistent with the TypeQL spec of overridden roles in 'plays' declarations being unscoped labels. 
  
  
- **Fix bug when overriding committed role-types with newly defined role-types**
  Fixes a bug which creates an invalid override when a committed role-type is overridden with a newly defined role-type.
  
  
- **Fix commit-time cleanup of chains of empty relations**
  
  In cases where chains of empty relations existed, for example `r1 -> r2 -> r3... -> rX`,  it was possible that TypeDB did not correctly execute the automatic relation cleanup due to the order the relations happened to be traversed.
  
  TypeDB now retries the commit-time cleanup of empty relations until there are no new deletions. We accept the higher runtime cost of this operation since 1) we expect the number of modified relations in a transaction to be relatively small (no more than thousands) 2) relation chains are relatively rare 3) relation chains that must clean up in dependent fashion are extremely rare.
  
  
- **Fix apt deployment: correct entry point script**
  
  When assembling a server + console bundle for apt deployment, the console entry point would sometimes override the bundle entry point, making the server inaccessible. We resolve that issue by explicitly filtering out the console entry point during assembly.
  


## Code Refactors
- **Generate persistent server ID**
  
  We update the server ID to be generated once, then persisted into the server's data directory. A fallback ID of of `_0` is also used if this fails for any reason.
  
  
- **Transition from standalone typedb-common to typeql/common**
  
  We update Bazel dependencies and target paths following the merging of typedb-common into [vaticle/typeql](https://github.com/vaticle/typeql/) (see https://github.com/vaticle/typeql/pull/313).
  
- **Migrate artifact hosting to cloudsmith**
  Updates artifact credentials, and deployment & consumption rules to use cloudsmith (repo.typedb.com) instead of the self-hosted sonatype repository (repo.vaticle.com).
  
  
- **Improve error message when using anonymous variable in delete query**
  
  We now throw an explicit error message when a user tries to delete a 'thing' variable that is not named.
  For example:
  ```
  match $x isa person;
  delete $x has name 'John';
  ```
  
  Will now explicitly throw an exception saying that the 'delete' variables must be matched first.
  
  
- **Disable diagnostics in Docker and Assembly tests**
  
  We disable diagnostics in Docker and Assembly tests. This also required allow the TypeDB Runner library to accept and transmit arguments to the binary it boots.
  
  
- **Ingest binary and test-runner package from common**
  
  We bring in the binary package and test-runner from typedb-common. `typedb-runner` is deployed to maven such that we can safely depend on it from other repos without creating Bazel dependency cycles.
  
- **Improve fatal error handling and shutdown**
  
  We streamline the error handling infrastructure by requiring all TypeDBExceptions have an associated error code, avoiding simple wrappers of Java or Storage exceptions without any context. We also ensure that all errors originating in TypeDB, such as TypeQL parser errors, are wrapped in TypeDBExceptions.
  
  The result is the following invariant:
  - TypeDB will always throw either unexpected Java exceptions, or TypeDBExceptions
  - All TypeDBExceptions have error messages and therefore error codes
  
  Additionally, Diagnostics are now implemented via a Singleton in order to inject different error reporting for TypeDB Core or Cloud. This means that all tests must now initialise a Diagnostics singleton to no-op via `Diagnostics.initialiseNoop()` before executing.
  
  

## Other Improvements
- **Pin CircleCI Ubuntu image to a specifici Ubuntu 20.04 release**

- **Explicitly install python tool dependencies**
  
  Since the upgrade to rules-python v0.24 (https://github.com/vaticle/dependencies/pull/460), we are required to explicitly install python dependencies in the WORKSPACE file. The python tools happened to be unused, so these errors were not visible until the sync dependencies tool was restored.
  
- **Sync dependencies in CI**
  
  We add a sync-dependencies job to be run in CI after successful snapshot and release deployments. The job sends a request to vaticle-bot to update all downstream dependencies.
  
  Note: this PR does _not_ update the `dependencies` repo dependency. It will be updated automatically by the bot during its first pass.
  
- **//ci:sync-dependencies: Set up CI filters for master-development workflow**

- **Catch any exceptions during error diagnostics configuration**

- **Diagnostics can be re-initialised to facilitate in-memory tests**

- **Disable diagnostics in Reasoner benchmarks and Reasoner behaviour tests**

- **Fix negated traversals so filters apply after the negation**
  Fixes a bug where the `get` clause filtered out concepts before the negation was applied. This lead to the negation being evaluated without that variable being bound.
  
  
- **Shorten diagnostic ID to 16 hex chars**

    
