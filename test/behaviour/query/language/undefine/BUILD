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


package(default_visibility = ["//visibility:__subpackages__"])
load("@vaticle_dependencies//tool/checkstyle:rules.bzl", "checkstyle_test")

java_test(
    name = "test",
    srcs = [
        "UndefineTest.java",
    ],
    test_class = "com.vaticle.typedb.core.test.behaviour.query.language.undefine.UndefineTest",
    deps = [
        # External dependencies from Maven
        "@maven//:io_cucumber_cucumber_junit",
    ],
    runtime_deps = [
        "//test/behaviour/query:steps",
        "//test/behaviour/connection:steps",
        "//test/behaviour/connection/session:steps",
        "//test/behaviour/config:parameters",
        "//test/behaviour/logic/rule:steps",
    ],
    data = [
        "@vaticle_typedb_behaviour//query/language:undefine.feature",
    ],
    size = "medium",
    visibility = ["//visibility:public"],
)

checkstyle_test(
    name = "checkstyle",
    include = glob(["*"]),
    license_type = "agpl-header",
)
