package org.wso2.sample.jwt.audience.handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.util.ArrayList;
import java.util.List;

public class AudienceConfiguration {

    private static Log log = LogFactory.getLog(AudienceConfiguration.class);

    public static List<String> getAudienceConfig(int tenantId) {

        List<String> audienceList = new ArrayList<>();
        try {
            IdentityTenantUtil.initializeRegistry(tenantId, OAuth2Util.getTenantDomain(tenantId));
            Registry registry = IdentityTenantUtil.getConfigRegistry(tenantId);
            String resourcePath = "identity/config/audiencevalues";
            if (registry.resourceExists(resourcePath)) {
                Resource resource = registry.get(resourcePath);
                String audience = "audience";
                Object audienceObject = resource.getProperties().get(audience);
                if (audienceObject instanceof List) {
                    if ((!((List) audienceObject).isEmpty()) && (((List) audienceObject).get(0) instanceof String))
                        audienceList = (ArrayList<String>) audienceObject;
                }
            }
        } catch (RegistryException e) {
            log.error("Error while getting data from the registry.", e);
        } catch (IdentityException e) {
            log.error("Error while getting the tenant domain from tenant id : " + tenantId, e);
        }
        return audienceList;
    }
}