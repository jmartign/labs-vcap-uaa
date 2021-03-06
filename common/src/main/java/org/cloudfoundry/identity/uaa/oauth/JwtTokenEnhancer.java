/*
 * Cloud Foundry 2012.02.03 Beta
 * Copyright (c) [2009-2012] VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product includes a number of subcomponents with
 * separate copyright notices and license terms. Your use of these
 * subcomponents is subject to the terms and conditions of the
 * subcomponent's license, as noted in the LICENSE file.
 */
package org.cloudfoundry.identity.uaa.oauth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.jwt.JwtHelper;
import org.springframework.security.jwt.crypto.sign.InvalidSignatureException;
import org.springframework.security.jwt.crypto.sign.MacSigner;
import org.springframework.security.jwt.crypto.sign.RsaSigner;
import org.springframework.security.jwt.crypto.sign.RsaVerifier;
import org.springframework.security.jwt.crypto.sign.Signer;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * OAuth2 token services that produces JWT encoded token values.
 * 
 * @author Dave Syer
 * @author Luke Taylor
 */
@Controller
public class JwtTokenEnhancer implements TokenEnhancer, InitializingBean {
	protected final Log logger = LogFactory.getLog(getClass());

	private AccessTokenConverter tokenConverter = new DefaultTokenConverter();

	private ObjectMapper objectMapper = new ObjectMapper();

	private String verifierKey = new RandomValueStringGenerator().generate();

	private Signer signer = new MacSigner(verifierKey);

	private String signingKey = verifierKey;

	/**
	 * @return the key used to verify tokens
	 */
	@RequestMapping(value = "/token_key", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, String> getKey() {
		Map<String,String> result = new LinkedHashMap<String, String>();
		result.put("alg", signer.algorithm());
		result.put("value", verifierKey);
		return result;
	}

	/**
	 * Sets the JWT signing key. It can be either a simple MAC key or an RSA key. RSA keys should be in OpenSSH format,
	 * as produced by <tt>ssh-keygen</tt>.
	 * 
	 * @param key the key to be used for signing JWTs.
	 */
	public void setSigningKey(String key) {
		Assert.hasText(key);
		key = key.trim();

		this.signingKey = key;

		if (key.startsWith("-----BEGIN")) {
			signer = new RsaSigner(key);
			logger.info("Configured with RSA signing key");
		}
		else {
			// Assume it's an HMAC key
			verifierKey = key;
			signer = new MacSigner(key);
		}
	}

	/**
	 * The key used for verifying signatures produced by this class. This is not used but is returned from the endpoint
	 * to allow resource servers to obtain the key.
	 * 
	 * For an HMAC key it will be the same value as the signing key and does not need to be set. For and RSA key, it
	 * should be set to the String representation of the public key, in a standard format (e.g. OpenSSH keys)
	 * 
	 * @param key the signature verification key (typically an RSA public key)
	 */
	public void setVerifierKey(String key) {
		this.verifierKey = key;
		try {
			new RsaSigner(verifierKey);
			throw new IllegalArgumentException("Private key cannot be set as verifierKey property");
		}
		catch (Exception expected) {
			// Expected
		}
	}

	public OAuth2AccessToken enhance(OAuth2AccessToken accessToken, OAuth2Authentication authentication) {
		DefaultOAuth2AccessToken result = new DefaultOAuth2AccessToken(accessToken);
		String tokenId = result.getValue();
		result.setAdditionalInformation(Collections.<String, Object> singletonMap("token_id", tokenId));
		return result.setValue(createAccessTokenValue(accessToken, authentication));
	}

	protected String createAccessTokenValue(OAuth2AccessToken accessToken, OAuth2Authentication authentication) {
		String content;
		try {
			content = objectMapper.writeValueAsString(tokenConverter.convertAccessToken(accessToken, authentication));
		}
		catch (Exception e) {
			throw new IllegalStateException("Cannot convert access token to JSON", e);
		}
		String token = JwtHelper.encode(content, signer).getEncoded();
		return token;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		// Check the signing and verification keys match
		if (signer instanceof RsaSigner) {
			RsaVerifier verifier;
			try {
				verifier = new RsaVerifier(verifierKey);
			}
			catch (Exception e) {
				logger.warn("Unable to create an RSA verifier from verifierKey");
				return;
			}

			byte[] test = "test".getBytes();
			try {
				verifier.verify(test, signer.sign(test));
				logger.info("Signing and verification RSA keys match");
			}
			catch (InvalidSignatureException e) {
				logger.error("Signing and verification RSA keys do not match");
			}
		}
		else {
			// Avoid a race condition where
			Assert.state(this.signingKey == this.verifierKey,
					"For MAC signing you do not need to specify the verifier key separately, and if you do it must match the signing key");
		}
	}
}
