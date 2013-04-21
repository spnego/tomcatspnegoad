/*
 * Copyright 2013 Michael Osipov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.michaelo.tomcat.authenticator;

/**
 * Authentication exception for server-side issues.
 *
 * @version $Id$
 */
public class AuthenticationException extends Exception {

	private static final long serialVersionUID = 1933003623124900749L;

	public AuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}

}
