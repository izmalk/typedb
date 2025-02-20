#
# Copyright (C) 2022 Vaticle
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

load("@vaticle_dependencies//tool/checkstyle:rules.bzl", "checkstyle_test")
load("@vaticle_dependencies//builder/java:rules.bzl", "native_java_libraries")

package(default_visibility = [
    "//test/benchmark/reasoner/iam:__subpackages__",
])

native_java_libraries(
    name = "common",
    srcs = ["BenchmarkRunner.java",
            "Benchmark.java",
            "QueryParams.java",
    ],
    native_libraries_deps = [
        "//:typedb",
        "//database:database",
        "//common:common",
        "//concurrent:concurrent",
        "//migrator:migrator",
        "//reasoner:reasoner",
    ],
    deps = [
        "//server:version",

        # External dependencies from Vaticle
        "@vaticle_typeql//common/java:common",
        "@vaticle_typeql//java:typeql-lang",
        "@vaticle_typeql//java/query",

        # External dependencies from Maven
        "@maven//:org_slf4j_slf4j_api",
        "@maven//:junit_junit",
    ]
)

checkstyle_test(
    name = "checkstyle",
    include = glob(["*"]),
    license_type = "agpl-header",
)
