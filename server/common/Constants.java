/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.core.server.common;

import java.io.File;
import java.nio.file.Path;

import static com.vaticle.typedb.core.server.common.Util.getTypedbDir;

public class Constants {

    static final File ASCII_LOGO_FILE = getTypedbDir().resolve("server/resources/typedb-ascii.txt").toFile();
    public static final Path CONFIG_PATH = getTypedbDir().resolve("server/conf/config.yml");
    public static final String TYPEDB_DISTRIBUTION_NAME = "TypeDB Core";
    public static final String TYPEDB_LOG_FILE_NAME = "typedb";
    public static final String TYPEDB_LOG_FILE_EXT = ".log";
    public static final String TYPEDB_LOG_ARCHIVE_EXT = ".log.gz";
    public static final String DIAGNOSTICS_REPORTING_URI = "https://3d710295c75c81492e57e1997d9e01e1@o4506315929812992.ingest.sentry.io/4506316048629760";
    public static final String SERVER_ID_FILE_NAME = "_server_id";
    public static final int SERVER_ID_LENGTH = 16;
    public static final String SERVER_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
}
