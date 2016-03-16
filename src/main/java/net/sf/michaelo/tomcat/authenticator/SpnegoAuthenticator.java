/*
 * Copyright 2013–2016 Michael Osipov
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

import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import net.sf.michaelo.tomcat.realm.GSSRealmBase;
import net.sf.michaelo.tomcat.utils.Base64;

import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;

/**
 * A SPNEGO Authenticator which utilizes GSS-API to authenticate a client.
 * <p>
 * This authenticator has the following configuration options:
 * <ul>
 * <li>{@code loginEntryName}: Login entry name with a configured {@code Krb5LoginModule}.</li>
 * </ul>
 *
 * @version $Id$
 */
/*
 * Error messages aren't reported correctly by the ErrorReportValve, see
 * http://www.mail-archive.com/users@tomcat.apache.org/msg98308.html Solution:
 * net.sf.michaelo.tomcat.extras.valves.EnhancedErrorReportValve
 */
public class SpnegoAuthenticator extends GSSAuthenticatorBase {

	protected static final String SPNEGO_METHOD = "SPNEGO";
	protected static final String SPNEGO_AUTH_SCHEME = "Negotiate";

	private static final byte[] NTLM_TYPE1_MESSAGE_START = { (byte) 'N', (byte) 'T', (byte) 'L',
			(byte) 'M', (byte) 'S', (byte) 'S', (byte) 'P', (byte) '\0', (byte) 0x01, (byte) 0x00,
			(byte) 0x00, (byte) 0x00 };

	@Override
	public String getInfo() {
		return "net.sf.michaelo.tomcat.authenticator.SpnegoAuthenticator/2.0";
	}

	@Override
	protected boolean authenticate(Request request, Response response, LoginConfig config)
			throws IOException {

		// HttpServletRequest request = req.getRequest();
		// HttpServletResponse response = resp.getResponse();

		Principal principal = request.getUserPrincipal();
		// String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
		if (principal != null) {
			if (logger.isDebugEnabled())
				logger.debug(sm.getString("authenticator.alreadyAuthenticated", principal));
			String ssoId = (String) request.getNote(Constants.REQ_SSOID_NOTE);
			if (ssoId != null)
				associate(ssoId, request.getSessionInternal(true));
			return true;
		}

		// NOTE: We don't try to reauthenticate using any existing SSO session,
		// because that will only work if the original authentication was
		// BASIC or FORM, which are less secure than the SPNEGO auth-type
		// specified for this webapp

		/*
		if (ssoId != null) {
			if (logger.isDebugEnabled())
				logger.debug(String.format("SSO Id %s set; attempting reauthentication", ssoId));

			if (reauthenticateFromSSO(ssoId, request))
				return true;
		}
		*/

		String authorization = request.getHeader("Authorization");

		if (!StringUtils.startsWithIgnoreCase(authorization, SPNEGO_AUTH_SCHEME)) {
			sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME);
			return false;
		}

		String authorizationValue = StringUtils.substring(authorization,
				SPNEGO_AUTH_SCHEME.length() + 1);

		if (StringUtils.isEmpty(authorizationValue)) {
			sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME);
			return false;
		}

		byte[] outToken = null;
		byte[] inToken = null;

		if (logger.isDebugEnabled())
			logger.debug(sm.getString("spnegoAuthenticator.processingToken", authorizationValue));

		try {
			inToken = Base64.decode(authorizationValue);
		} catch (Exception e) {
			logger.warn(sm.getString("spnegoAuthenticator.incorrectlyEncodedToken",
					authorizationValue), e);

			sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME,
					"spnegoAuthenticator.incorrectlyEncodedToken.responseMessage");
			return false;
		}

		if (inToken.length >= NTLM_TYPE1_MESSAGE_START.length) {
			boolean ntlmDetected = false;
			for (int i = 0; i < NTLM_TYPE1_MESSAGE_START.length; i++) {
				ntlmDetected = inToken[i] == NTLM_TYPE1_MESSAGE_START[i];

				if (!ntlmDetected)
					break;
			}

			if (ntlmDetected) {
				logger.warn(sm.getString("spnegoAuthenticator.ntlmNotSupported"));

				sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME,
						"spnegoAuthenticator.ntlmNotSupported.responseMessage");
				return false;
			}
		}

		LoginContext lc = null;
		GSSContext gssContext = null;

		try {
			try {
				lc = new LoginContext(getLoginEntryName());
				lc.login();
			} catch (LoginException e) {
				logger.error(sm.getString("spnegoAuthenticator.obtainFailed"), e);

				sendInternalServerError(request, response, "spnegoAuthenticator.obtainFailed");
				return false;
			}

			final GSSManager manager = GSSManager.getInstance();
			final PrivilegedExceptionAction<GSSCredential> action = new PrivilegedExceptionAction<GSSCredential>() {
				@Override
				public GSSCredential run() throws GSSException {
					return manager.createCredential(null, GSSCredential.INDEFINITE_LIFETIME,
							SPNEGO_MECHANISM, GSSCredential.ACCEPT_ONLY);
				}
			};

			try {
				gssContext = manager.createContext(Subject.doAs(lc.getSubject(), action));
			} catch (PrivilegedActionException e) {
				logger.error(sm.getString("spnegoAuthenticator.obtainFailed"), e.getException());

				sendInternalServerError(request, response, "spnegoAuthenticator.obtainFailed");
				return false;
			} catch (GSSException e) {
				logger.error(sm.getString("spnegoAuthenticator.createContextFailed"), e);

				sendInternalServerError(request, response,
						"spnegoAuthenticator.createContextFailed");
				return false;
			}

			try {
				outToken = gssContext.acceptSecContext(inToken, 0, inToken.length);
			} catch (GSSException e) {
				logger.warn(sm.getString("spnegoAuthenticator.invalidToken", authorizationValue), e);

				sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME,
						"spnegoAuthenticator.invalidToken.responseMessage");
				return false;
			}

			try {
				if (gssContext.isEstablished()) {
					if (logger.isDebugEnabled())
						logger.debug(sm.getString("spnegoAuthenticator.contextSuccessfullyEstablished"));

					GSSRealmBase<?> realm = (GSSRealmBase<?>) context.getRealm();
					principal = realm.authenticate(gssContext);

					if (principal == null) {
						GSSName srcName = gssContext.getSrcName();
						sendUnauthorized(request, response, SPNEGO_AUTH_SCHEME,
								"authenticator.userNotFound", srcName);
						return false;
					}
				} else {
					logger.error(sm.getString("spnegoAuthenticator.continueContextNotSupported"));

					sendInternalServerError(request, response,
							"spnegoAuthenticator.continueContextNotSupported.responseMessage");
					return false;
				}

			} catch (GSSException e) {
				logger.error(sm.getString("spnegoAuthenticator.inquireFailed"), e);

				sendInternalServerError(request, response, "spnegoAuthenticator.inquireFailed");
				return false;
			}

		} finally {
			if (gssContext != null) {
				try {
					gssContext.dispose();
				} catch (GSSException e) {
					; // Ignore
				}
			}
			if (lc != null) {
				try {
					lc.logout();
				} catch (LoginException e) {
					; // Ignore
				}
			}
		}

		register(request, response, principal, SPNEGO_METHOD, principal.getName(), null);

		if (ArrayUtils.isNotEmpty(outToken)) {
			String authenticationValue = Base64.encode(outToken);

			if (logger.isDebugEnabled())
				logger.debug(sm.getString("spnegoAuthenticator.respondingWithToken", authenticationValue));

			response.setHeader("WWW-Authenticate",
					SPNEGO_AUTH_SCHEME + " " + authenticationValue);
		}

		return true;
	}

}
