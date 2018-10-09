package org.wso2.sample.jwt.audience.handler.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.wso2.sample.jwt.audience.handler.CustomJWTBearerGrantHandler;

/**
 * @scr.component name="sample.custom.jwt.grant.handler.dsComponent" immediate=true
 */
public class CustomJWTBearerGrantHandlerDSComponent {

    private static Log log = LogFactory.getLog(CustomJWTBearerGrantHandlerDSComponent.class);

    protected void activate(ComponentContext context) {

        CustomJWTBearerGrantHandler customJWTBearerGrantHandler = new
                CustomJWTBearerGrantHandler();
        //register the custom listener as an OSGI service.
        context.getBundleContext().registerService(
                CustomJWTBearerGrantHandler.class.getName(), customJWTBearerGrantHandler, null);

        log.info("CustomJWTBearerGrantHandlerDSComponent bundle activated successfully..");
    }

    protected void deactivate(ComponentContext context) {
        if (log.isDebugEnabled()) {
            log.debug("CustomJWTBearerGrantHandlerDSComponent is deactivated ");
        }
    }

}
