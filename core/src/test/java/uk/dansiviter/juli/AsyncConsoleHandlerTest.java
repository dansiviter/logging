/*
 * Copyright 2021 Daniel Siviter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.dansiviter.juli;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AsyncConsoleHandler}.
 */
public class AsyncConsoleHandlerTest {
	private LogManager manager;

	@BeforeEach
	void before() {
		manager = LogManager.getLogManager();
	}

	@Test
	public void init() throws IOException {
		var handler = new AsyncConsoleHandler();
		assertThat(handler.isStdOut(), is(true));
	}

	@Test
	public void init_stdErr() throws IOException {
		var config = format(
			"%s.stdOut=%s\n",
			AsyncConsoleHandler.class.getName(),
			false);
		try (InputStream is = new ByteArrayInputStream(config.getBytes())) {
			this.manager.readConfiguration(is);
		}

		var handler = new AsyncConsoleHandler();
		assertThat(handler.isStdOut(), is(false));
	}

	@AfterAll
	public static void afterAll() {
		LogManager.getLogManager().reset();
	}
}
