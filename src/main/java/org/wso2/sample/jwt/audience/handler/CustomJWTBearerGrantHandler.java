package org.wso2.sample.jwt.audience.handler;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.FederatedAuthenticatorConfig;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.application.common.model.IdentityProviderProperty;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.grant.jwt.JWTBearerGrantHandler;
import org.wso2.carbon.identity.oauth2.grant.jwt.JWTConstants;
import org.wso2.carbon.identity.oauth2.grant.jwt.cache.JWTCache;
import org.wso2.carbon.identity.oauth2.grant.jwt.cache.JWTCacheEntry;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.idp.mgt.IdentityProviderManager;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomJWTBearerGrantHandler extends JWTBearerGrantHandler {

    private static final String OAUTH_SPLIT_AUTHZ_USER_3_WAY = "OAuth.SplitAuthzUser3Way";
    private static final String DEFAULT_IDP_NAME = "default";
    private static Log log = LogFactory.getLog(CustomJWTBearerGrantHandler.class);
    private static final String OIDC_IDP_ENTITY_ID = "IdPEntityId";
    private static final String ERROR_GET_RESIDENT_IDP =
            "Error while getting Resident Identity Provider of '%s' tenant.";
    private static final String JWKS_VALIDATION_ENABLE_CONFIG = "JWTValidatorConfigs.Enable";
    private static final String JWKS_URI = "jwksUri";

    private String tenantDomain;
    private static Map<Integer, Key> privateKeys = new ConcurrentHashMap<>();
    private static final String JWT_ASSERTION_CLAIM = "JWT_ASSERTION_CLAIM";

    private int validityPeriod;
    private JWTCache jwtCache;
    private boolean cacheUsedJTI;

    /**
     * Initialize the JWT cache.
     *
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    @Override
    public void init() throws IdentityOAuth2Exception {
        super.init();
        String resourceName = JWTConstants.PROPERTIES_FILE;

        ClassLoader loader = JWTBearerGrantHandler.class.getClassLoader();
        Properties prop = new Properties();
        InputStream resourceStream = loader.getResourceAsStream(resourceName);
        try {
            prop.load(resourceStream);
            validityPeriod = Integer.parseInt(prop.getProperty(JWTConstants.VALIDITY_PERIOD));
            cacheUsedJTI = Boolean.parseBoolean(prop.getProperty(JWTConstants.CACHE_USED_JTI));
            if (cacheUsedJTI) {
                this.jwtCache = JWTCache.getInstance();
            }
        } catch (IOException e) {
            throw new IdentityOAuth2Exception("Can not find the file", e);
        } catch (NumberFormatException e) {
            throw new IdentityOAuth2Exception("Invalid Validity period", e);
        } finally {
            try {
                resourceStream.close();
            } catch (IOException e) {
                log.error("Error while closing the stream");
            }
        }
    }

    /**
     * Get resident Identity Provider.
     *
     * @param tenantDomain tenant Domain
     * @param jwtIssuer    issuer extracted from assertion
     * @return resident Identity Provider
     * @throws IdentityOAuth2Exception
     */
    private IdentityProvider getResidentityIDPForIssuer(String tenantDomain, String jwtIssuer) throws IdentityOAuth2Exception {

        String issuer = StringUtils.EMPTY;
        IdentityProvider residentIdentityProvider = null;
        try {
            residentIdentityProvider = IdentityProviderManager.getInstance().getResidentIdP(tenantDomain);
        } catch (IdentityProviderManagementException e) {
            String errorMsg = String.format(ERROR_GET_RESIDENT_IDP, tenantDomain);
            throw new IdentityOAuth2Exception(errorMsg, e);
        }
        FederatedAuthenticatorConfig[] fedAuthnConfigs = residentIdentityProvider.getFederatedAuthenticatorConfigs();
        FederatedAuthenticatorConfig oauthAuthenticatorConfig =
                IdentityApplicationManagementUtil.getFederatedAuthenticator(fedAuthnConfigs,
                        IdentityApplicationConstants.Authenticator.OIDC.NAME);
        if (oauthAuthenticatorConfig != null) {
            issuer = IdentityApplicationManagementUtil.getProperty(oauthAuthenticatorConfig.getProperties(),
                    OIDC_IDP_ENTITY_ID).getValue();
        }
        return jwtIssuer.equals(issuer) ? residentIdentityProvider : null;
    }

    /**
     * We're validating the JWT token that we receive from the request. Through the assertion parameter is the POST
     * request. A request format that we handle here looks like,
     * <p/>
     * POST /token.oauth2 HTTP/1.1
     * Host: as.example.com
     * Content-Type: application/x-www-form-urlencoded
     * <p/>
     * grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer
     * &assertion=eyJhbGciOiJFUzI1NiJ9.
     * eyJpc3Mi[...omitted for brevity...].
     *
     * @param tokReqMsgCtx Token message request context
     * @return true if validation is successful, false otherwise
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        //super.validateGrant(tokReqMsgCtx); //This line was commented to work with IS 5.2.0

        SignedJWT signedJWT = null;
        IdentityProvider identityProvider;
        List<String> tokenEndPointAliases = null;
        JWTClaimsSet claimsSet = null;

        tenantDomain = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        if (StringUtils.isEmpty(tenantDomain)) {
            tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }
        //Check whether the assertion is encrypted.
        EncryptedJWT encryptedJWT = getEncryptedJWT(tokReqMsgCtx);
        if (encryptedJWT == null) {
            if (log.isDebugEnabled()) {
                log.debug("The assertion is not encrypted.");
            }
            //The assertion is not an encrypted one.
            signedJWT = getSignedJWT(tokReqMsgCtx);
            if (signedJWT == null) {
                handleException("No Valid Assertion was found for " + JWTConstants.OAUTH_JWT_BEARER_GRANT_TYPE);
            } else {
                claimsSet = getClaimSet(signedJWT);
            }

        } else {
            // The assertion is encrypted.
            RSAPrivateKey rsaPrivateKey = getPrivateKey(tenantDomain);
            RSADecrypter decrypter = new RSADecrypter(rsaPrivateKey);
            try {
                encryptedJWT.decrypt(decrypter);
                if (log.isDebugEnabled()) {
                    log.debug("The assertion is successfully decrypted.");
                }
            } catch (JOSEException e) {
                handleException("Error when decrypting the encrypted JWT." + e.getMessage());
            }
            try {
                // If the assertion is a nested JWT.
                String payload = null;
                if (encryptedJWT.getPayload() != null) {
                    payload = encryptedJWT.getPayload().toString();
                }
                if (!isEncryptedJWTSigned(payload)) {
                    try {
                        // If encrypted JWT is not signed.
                        claimsSet = encryptedJWT.getJWTClaimsSet();
                        if (log.isDebugEnabled()) {
                            log.debug("The encrypted JWT is not signed. Obtained the claim set of the encrypted JWT.");
                        }
                    } catch (ParseException ex) {
                        handleException("Error when trying to retrieve claimsSet from the encrypted JWT." +
                                ex.getMessage());
                    }
                } else {
                    // If encrypted JWT is not signed.
                    signedJWT = SignedJWT.parse(payload);
                    claimsSet = getClaimSet(signedJWT);
                    if (log.isDebugEnabled()) {
                        log.debug("The encrypted JWT is signed. Obtained the claim set of the encrypted JWT.");
                    }
                }
            } catch (ParseException e) {
                handleException("Unexpected number of Base64URL parts of the nested JWT payload. Expected number" +
                        " of parts must be three. " + e.getMessage());
            }
        }
        if (claimsSet == null) {
            handleException("Claim values are empty in the given JSON Web Token");
        }

        String jwtIssuer = claimsSet.getIssuer();
        String subject = resolveSubject(claimsSet);
        List<String> audience = claimsSet.getAudience();
        Date expirationTime = claimsSet.getExpirationTime();
        Date notBeforeTime = claimsSet.getNotBeforeTime();
        Date issuedAtTime = claimsSet.getIssueTime();
        String jti = claimsSet.getJWTID();
        Map<String, Object> customClaims = new HashMap<>();
        Set<String> claimsKeySet = claimsSet.getClaims().keySet();
        for (String claimKey : claimsKeySet) {
            if (!JWTClaimsSet.getRegisteredNames().contains(claimKey)) {
                customClaims.put(claimKey, claimsSet.getClaim(claimKey));
            }
        }
        boolean signatureValid;
        boolean audienceFound = false;
        long currentTimeInMillis = System.currentTimeMillis();
        long timeStampSkewMillis = OAuthServerConfiguration.getInstance().getTimeStampSkewInSeconds() * 1000;

        if (StringUtils.isEmpty(jwtIssuer) || StringUtils.isEmpty(subject) || expirationTime == null || audience == null) {
            handleException("Mandatory fields(Issuer, Subject, Expiration time or Audience) are empty in the given JSON Web Token.");
        }
        try {
            identityProvider = IdentityProviderManager.getInstance().getIdPByName(jwtIssuer, tenantDomain);
            if (identityProvider != null) {
                // if no IDPs were found for a given name, the IdentityProviderManager returns a dummy IDP with the
                // name "default". We need to handle this case.
                if (StringUtils.equalsIgnoreCase(identityProvider.getIdentityProviderName(), DEFAULT_IDP_NAME)) {
                    //check whether this jwt was issued by the resident identity provider
                    identityProvider = getResidentityIDPForIssuer(tenantDomain, jwtIssuer);
                    if (identityProvider == null) {
                        handleException("No Registered IDP found for the JWT with issuer name : " + jwtIssuer);
                    }
                }

                tokenEndPointAliases = getTokenEndpointAlias(identityProvider);
            } else {
                handleException("No Registered IDP found for the JWT with issuer name : " + jwtIssuer);
            }

            signatureValid = validateSignature(signedJWT, identityProvider);
            if (signatureValid) {
                if (log.isDebugEnabled()) {
                    log.debug("Signature/MAC validated successfully.");
                }
            } else {
                handleException("Signature or Message Authentication invalid.");
            }

            if (Boolean.parseBoolean(IdentityUtil.getProperty(OAUTH_SPLIT_AUTHZ_USER_3_WAY))) {
                tokReqMsgCtx.setAuthorizedUser(OAuth2Util.getUserFromUserName(subject));
            } else {
                tokReqMsgCtx.setAuthorizedUser(AuthenticatedUser
                        .createLocalAuthenticatedUserFromSubjectIdentifier(subject));
            }
            if (log.isDebugEnabled()) {
                log.debug("Subject(sub) found in JWT: " + subject);
                log.debug(subject + " set as the Authorized User.");
            }

            tokReqMsgCtx.setScope(tokReqMsgCtx.getOauth2AccessTokenReqDTO().getScope());

            if (CollectionUtils.isEmpty(tokenEndPointAliases)) {
                handleException("Audience of the local Identity Provider has not been " +
                        "configured for " + identityProvider.getIdentityProviderName());
            }
            if (audience != null && CollectionUtils.isNotEmpty(audience) && StringUtils.isNotBlank(audience.get(0))) {
                for (String tokenEndpointAlias : tokenEndPointAliases) {

                    if (StringUtils.equals(tokenEndpointAlias, audience.get(0))) {
                        if (log.isDebugEnabled()) {
                            log.debug(tokenEndPointAliases + " of IDP was found in the list of audiences.");
                        }
                        audienceFound = true;
                        break;
                    }
                }
            }

            if (!audienceFound) {
                handleException("None of the audience values matched the tokenEndpoint Alias " + tokenEndPointAliases);
            }
            boolean checkedExpirationTime = checkExpirationTime(expirationTime, currentTimeInMillis,
                    timeStampSkewMillis);
            if (checkedExpirationTime) {
                if (log.isDebugEnabled()) {
                    log.debug("Expiration Time(exp) of JWT was validated successfully.");
                }
            }
            if (notBeforeTime == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Not Before Time(nbf) not found in JWT. Continuing Validation");
                }
            } else {
                boolean checkedNotBeforeTime = checkNotBeforeTime(notBeforeTime, currentTimeInMillis,
                        timeStampSkewMillis);
                if (checkedNotBeforeTime) {
                    if (log.isDebugEnabled()) {
                        log.debug("Not Before Time(nbf) of JWT was validated successfully.");
                    }
                }
            }
            if (issuedAtTime == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Issued At Time(iat) not found in JWT. Continuing Validation");
                }
            } else {
                boolean checkedValidityToken = checkValidityOfTheToken(issuedAtTime, currentTimeInMillis,
                        timeStampSkewMillis);
                if (checkedValidityToken) {
                    if (log.isDebugEnabled()) {
                        log.debug("Issued At Time(iat) of JWT was validated successfully.");
                    }
                }
            }
            if (cacheUsedJTI && (jti != null)) {
                JWTCacheEntry entry = (JWTCacheEntry) jwtCache.getValueFromCache(jti);
                if (entry != null) {
                    if (checkCachedJTI(jti, signedJWT, entry, currentTimeInMillis, timeStampSkewMillis)) {
                        if (log.isDebugEnabled()) {
                            log.debug("JWT id: " + jti + " not found in the cache.");
                            log.debug("jti of the JWT has been validated successfully.");
                        }
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    if (!cacheUsedJTI) {
                        log.debug("List of used JSON Web Token IDs are not maintained. Continue Validation");
                    }
                    if (jti == null) {
                        log.debug("JSON Web Token ID(jti) not found in JWT. Continuing Validation");
                    }
                }
            }
            if (customClaims == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No custom claims found. Continue validating other claims.");
                }
            } else {
                boolean customClaimsValidated = validateCustomClaims(customClaims);
                if (!customClaimsValidated) {
                    handleException("Custom Claims in the JWT were invalid");
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("JWT Token was validated successfully");
            }
            if (cacheUsedJTI) {
                jwtCache.addToCache(jti, new JWTCacheEntry(signedJWT));
            }
            if (log.isDebugEnabled()) {
                log.debug("JWT Token was added to the cache successfully");
            }
        } catch (IdentityProviderManagementException e) {
            handleException("Error while getting the Federated Identity Provider ");
        } catch (JOSEException e) {
            handleException("Error when verifying signature");
        }
        if (log.isDebugEnabled()) {
            log.debug("Issuer(iss) of the JWT validated successfully");
        }

        tokReqMsgCtx.addProperty(JWT_ASSERTION_CLAIM, customClaims);
        return true;
    }

    private static Map<ClaimMapping, String> buildClaimMappings(Map<String, Object> attributeValue) {

        Map<ClaimMapping, String> claimMap = new HashMap<>();
        for (Object entryObject : attributeValue.entrySet()) {
            Map.Entry<String, Object> entry = (Map.Entry<String, Object>) entryObject;
            if (entry.getValue() != null) {
                String value = entry.getValue().toString();
                claimMap.put(ClaimMapping.build((String) entry.getKey(), (String) entry.getKey(), (String) null, false),
                        value);
            }
        }

        return claimMap;
    }

    /**
     * the default implementation creates the subject from the Sub attribute.
     * To translate between the federated and local user store, this may need some mapping.
     * Override if needed
     *
     * @param claimsSet all the JWT claims
     * @return The subject, to be used
     */
    protected String resolveSubject(JWTClaimsSet claimsSet) {

        return claimsSet.getSubject();
    }

    /**
     * @param tokReqMsgCtx Token message request context
     * @return signedJWT
     */
    private SignedJWT getSignedJWT(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        RequestParameter[] params = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();
        String assertion = null;
        SignedJWT signedJWT = null;
        for (RequestParameter param : params) {
            if (param.getKey().equals(JWTConstants.OAUTH_JWT_ASSERTION)) {
                assertion = param.getValue()[0];
                break;
            }
        }
        if (StringUtils.isEmpty(assertion)) {
            return null;
        }

        try {
            signedJWT = SignedJWT.parse(assertion);
            if (log.isDebugEnabled()) {
                logJWT(signedJWT);
            }
        } catch (ParseException e) {
            handleException("Error while parsing the JWT" + e.getMessage());
        }
        return signedJWT;
    }

    /**
     * @param signedJWT Signed JWT
     * @return Claim set
     */
    private JWTClaimsSet getClaimSet(SignedJWT signedJWT) throws IdentityOAuth2Exception {

        JWTClaimsSet claimsSet = null;

        try {
            claimsSet = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            handleException("Error when trying to retrieve claimsSet from the JWT");
        }
        return claimsSet;
    }

    /**
     * Get token endpoint alias
     *
     * @param identityProvider Identity provider
     * @return token endpoint alias
     */
    private List<String> getTokenEndpointAlias(IdentityProvider identityProvider) throws IdentityOAuth2Exception {

        Property oauthTokenURL = null;
        List<String> audienceList = new ArrayList<>();
        if (IdentityApplicationConstants.RESIDENT_IDP_RESERVED_NAME.equals(
                identityProvider.getIdentityProviderName())) {
            try {
                identityProvider = IdentityProviderManager.getInstance().getResidentIdP(tenantDomain);
            } catch (IdentityProviderManagementException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error while getting Resident IDP :" + e.getMessage());
                }
            }
            FederatedAuthenticatorConfig[] fedAuthnConfigs =
                    identityProvider.getFederatedAuthenticatorConfigs();
            FederatedAuthenticatorConfig oauthAuthenticatorConfig =
                    IdentityApplicationManagementUtil.getFederatedAuthenticator(fedAuthnConfigs,
                            IdentityApplicationConstants.Authenticator.OIDC.NAME);

            if (oauthAuthenticatorConfig != null) {
                oauthTokenURL = IdentityApplicationManagementUtil.getProperty(
                        oauthAuthenticatorConfig.getProperties(),
                        IdentityApplicationConstants.Authenticator.OIDC.OAUTH2_TOKEN_URL);
            }
            if (oauthTokenURL != null) {
                audienceList.add(oauthTokenURL.getValue());
                if (log.isDebugEnabled()) {
                    log.debug("Token End Point Alias of Resident IDP :" + audienceList);
                }
            }
        } else {
            audienceList = AudienceConfiguration.getAudienceConfig(OAuth2Util.getTenantId(tenantDomain));
            if (log.isDebugEnabled()) {
                log.debug("Audience of the Federated IDP: " + audienceList);
            }
        }
        return audienceList;
    }

    /**
     * The JWT MUST contain an exp (expiration) claim that limits the time window during which
     * the JWT can be used. The authorization server MUST reject any JWT with an expiration time
     * that has passed, subject to allowable clock skew between systems. Note that the
     * authorization server may reject JWTs with an exp claim value that is unreasonably far in the
     * future.
     *
     * @param expirationTime      Expiration time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkExpirationTime(Date expirationTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {

        long expirationTimeInMillis = expirationTime.getTime();
        if ((currentTimeInMillis + timeStampSkewMillis) > expirationTimeInMillis) {
            handleException("JSON Web Token is expired." +
                    ", Expiration Time(ms) : " + expirationTimeInMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * The JWT MAY contain an nbf (not before) claim that identifies the time before which the
     * token MUST NOT be accepted for processing.
     *
     * @param notBeforeTime       Not before time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkNotBeforeTime(Date notBeforeTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {

        long notBeforeTimeMillis = notBeforeTime.getTime();
        if (currentTimeInMillis + timeStampSkewMillis < notBeforeTimeMillis) {
            handleException("JSON Web Token is used before Not_Before_Time." +
                    ", Not Before Time(ms) : " + notBeforeTimeMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * The JWT MAY contain an iat (issued at) claim that identifies the time at which the JWT was
     * issued. Note that the authorization server may reject JWTs with an iat claim value that is
     * unreasonably far in the past
     *
     * @param issuedAtTime        Token issued time
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Time skew
     * @return true or false
     */
    private boolean checkValidityOfTheToken(Date issuedAtTime, long currentTimeInMillis, long timeStampSkewMillis) throws IdentityOAuth2Exception {

        long issuedAtTimeMillis = issuedAtTime.getTime();
        long rejectBeforeMillis = 1000L * 60 * validityPeriod;
        if (currentTimeInMillis + timeStampSkewMillis - issuedAtTimeMillis >
                rejectBeforeMillis) {
            handleException("JSON Web Token is issued before the allowed time." +
                    ", Issued At Time(ms) : " + issuedAtTimeMillis +
                    ", Reject before limit(ms) : " + rejectBeforeMillis +
                    ", TimeStamp Skew : " + timeStampSkewMillis +
                    ", Current Time : " + currentTimeInMillis + ". JWT Rejected and validation terminated");
        }
        return true;
    }

    /**
     * Method to check whether the JTI is already in the cache.
     *
     * @param jti                 JSON Token Id
     * @param signedJWT           Signed JWT
     * @param entry               Cache entry
     * @param currentTimeInMillis Current time
     * @param timeStampSkewMillis Skew time
     * @return true or false
     */
    private boolean checkCachedJTI(String jti, SignedJWT signedJWT, JWTCacheEntry entry, long currentTimeInMillis,
                                   long timeStampSkewMillis) throws IdentityOAuth2Exception {

        try {
            SignedJWT cachedJWT = entry.getJwt();
            long cachedJWTExpiryTimeMillis = cachedJWT.getJWTClaimsSet().getExpirationTime().getTime();
            if (currentTimeInMillis + timeStampSkewMillis > cachedJWTExpiryTimeMillis) {
                if (log.isDebugEnabled()) {
                    log.debug("JWT Token has been reused after the allowed expiry time : "
                            + cachedJWT.getJWTClaimsSet().getExpirationTime());
                }

                // Update the cache with the new JWT for the same JTI.
                jwtCache.addToCache(jti, new JWTCacheEntry(signedJWT));
                if (log.isDebugEnabled()) {
                    log.debug("jti of the JWT has been validated successfully and cache updated");
                }
            } else {
                handleException("JWT Token \n" + signedJWT.getHeader().toJSONObject().toString() + "\n"
                        + signedJWT.getPayload().toJSONObject().toString() + "\n" +
                        "Has been replayed before the allowed expiry time : "
                        + cachedJWT.getJWTClaimsSet().getExpirationTime());
            }
        } catch (ParseException e) {
            handleException("Unable to parse the cached jwt assertion : " + entry.getEncodedJWt());
        }
        return true;
    }

    /**
     * @param signedJWT the signedJWT to be logged
     */
    private void logJWT(SignedJWT signedJWT) {

        log.debug("JWT Header: " + signedJWT.getHeader().toJSONObject().toString());
        log.debug("JWT Payload: " + signedJWT.getPayload().toJSONObject().toString());
        log.debug("Signature: " + signedJWT.getSignature().toString());
    }

    /**
     * Method to validate the signature of the JWT
     *
     * @param signedJWT signed JWT whose signature is to be verified
     * @param idp       Identity provider who issued the signed JWT
     * @return whether signature is valid, true if valid else false
     * @throws com.nimbusds.jose.JOSEException
     * @throws org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception
     */
    private boolean validateSignature(SignedJWT signedJWT, IdentityProvider idp)
            throws JOSEException, IdentityOAuth2Exception {

        boolean isJWKSEnabled = false;
        boolean hasJWKSUri = false;
        String jwksUri = null;

        String isJWKSEnalbedProperty = IdentityUtil.getProperty(JWKS_VALIDATION_ENABLE_CONFIG);
        isJWKSEnabled = Boolean.parseBoolean(isJWKSEnalbedProperty);
        if (isJWKSEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("JWKS based JWT validation enabled.");
            }
            IdentityProviderProperty[] identityProviderProperties = idp.getIdpProperties();
            if (!ArrayUtils.isEmpty(identityProviderProperties)) {
                for (IdentityProviderProperty identityProviderProperty : identityProviderProperties) {
                    if (StringUtils.equals(identityProviderProperty.getName(), JWKS_URI)) {
                        hasJWKSUri = true;
                        jwksUri = identityProviderProperty.getValue();
                        if (log.isDebugEnabled()) {
                            log.debug("JWKS endpoint set for the identity provider : " + idp
                                    .getIdentityProviderName() + ", jwks_uri : " + jwksUri);
                        }
                        break;
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("JWKS endpoint not specified for the identity provider : " + idp
                                    .getIdentityProviderName());
                        }
                    }
                }
            }
        }
        if (isJWKSEnabled && hasJWKSUri) {
            JWKSBasedJWTValidator jwksBasedJWTValidator = new JWKSBasedJWTValidator();
            return jwJWKSBasedJWTValidatorksBasedJWTValidator.validateSignature(signedJWT.getParsedString(), jwksUri, signedJWT.getHeader().getAlgorithm()
                    .getName(), null);
        } else {
            JWSVerifier verifier = null;
            JWSHeader header = signedJWT.getHeader();
            X509Certificate x509Certificate = resolveSignerCertificate(header, idp);
            if (x509Certificate == null) {
                handleException(
                        "Unable to locate certificate for Identity Provider " + idp.getDisplayName() + "; JWT " +
                                header.toString());
            }

            String alg = signedJWT.getHeader().getAlgorithm().getName();
            if (StringUtils.isEmpty(alg)) {
                handleException("Algorithm must not be null.");
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Signature Algorithm found in the JWT Header: " + alg);
                }
                if (alg.startsWith("RS")) {
                    // At this point 'x509Certificate' will never be null.
                    PublicKey publicKey = x509Certificate.getPublicKey();
                    if (publicKey instanceof RSAPublicKey) {
                        verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
                    } else {
                        handleException("Public key is not an RSA public key.");
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Signature Algorithm not supported yet : " + alg);
                    }
                }
                if (verifier == null) {
                    handleException("Could not create a signature verifier for algorithm type: " + alg);
                }
            }

            // At this point 'verifier' will never be null;
            return signedJWT.verify(verifier);
        }
    }

    /**
     * The default implementation resolves one certificate to Identity Provider and ignores the JWT header.
     * Override this method, to resolve and enforce the certificate in any other way
     * such as x5t attribute of the header.
     *
     * @param header The JWT header. Some of the x attributes may provide certificate information.
     * @param idp    The identity provider, if you need it.
     * @return the resolved X509 Certificate, to be used to validate the JWT signature.
     * @throws IdentityOAuth2Exception something goes wrong.
     */
    protected X509Certificate resolveSignerCertificate(JWSHeader header,
                                                       IdentityProvider idp) throws IdentityOAuth2Exception {

        X509Certificate x509Certificate = null;
        try {
            x509Certificate = (X509Certificate) IdentityApplicationManagementUtil
                    .decodeCertificate(idp.getCertificate());
        } catch (CertificateException e) {
            handleException("Error occurred while decoding public certificate of Identity Provider "
                    + idp.getIdentityProviderName() + " for tenant domain " + tenantDomain);
        }
        return x509Certificate;
    }

    private void handleException(String errorMessage) throws IdentityOAuth2Exception {

        log.error(errorMessage);
        throw new IdentityOAuth2Exception(errorMessage);
    }

    private EncryptedJWT getEncryptedJWT(OAuthTokenReqMessageContext tokReqMsgCtx) {

        RequestParameter[] params = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();
        String assertion = null;
        if (params != null) {
            for (RequestParameter param : params) {
                if (JWTConstants.OAUTH_JWT_ASSERTION.equals(param.getKey())) {
                    assertion = param.getValue()[0];
                    break;
                }
            }
        }
        if (StringUtils.isEmpty(assertion)) {
            if (log.isDebugEnabled()) {
                log.debug("The assertion is empty.");
            }
            return null;
        }

        try {
            EncryptedJWT encryptedJWT = EncryptedJWT.parse(assertion);
            return encryptedJWT;
        } catch (ParseException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while parsing the assertion. The assertion is not encrypted.");
            }
            return null;
        }
    }

    private static RSAPrivateKey getPrivateKey(String tenantDomain) throws IdentityOAuth2Exception {

        Key privateKey;
        int tenantId = OAuth2Util.getTenantId(tenantDomain);
        if (!(privateKeys.containsKey(tenantId))) {

            try {
                IdentityTenantUtil.initializeRegistry(tenantId, tenantDomain);
            } catch (IdentityException e) {
                throw new IdentityOAuth2Exception("Error occurred while loading registry for tenant " + tenantDomain,
                        e);
            }
            // get tenant's key store manager
            KeyStoreManager tenantKSM = KeyStoreManager.getInstance(tenantId);

            if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                // derive key store name
                String ksName = tenantDomain.trim().replace(".", "-");
                String jksName = ksName + ".jks";
                // obtain private key
                privateKey = tenantKSM.getPrivateKey(jksName, tenantDomain);

            } else {
                try {
                    privateKey = tenantKSM.getDefaultPrivateKey();
                } catch (Exception e) {
                    //Intentionally catch Exception as an Exception is thrown from the above layer.
                    throw new IdentityOAuth2Exception("Error while obtaining private key for super tenant", e);
                }
            }
            //privateKey will not be null always
            privateKeys.put(tenantId, privateKey);
        } else {
            //privateKey will not be null because containsKey() true says given key is exist and ConcurrentHashMap
            // does not allow to store null values
            privateKey = privateKeys.get(tenantId);
        }
        return (RSAPrivateKey) privateKey;
    }

    private boolean isEncryptedJWTSigned(String payload) {

        if (StringUtils.isNotEmpty(payload)) {
            String[] parts = payload.split(".");
            if (parts.length == 3) {
                if (StringUtils.isNotEmpty(parts[2].toString())) {
                    return true;
                }
                return false;
            }
            return false;
        } else {
            return false;
        }
    }
}
