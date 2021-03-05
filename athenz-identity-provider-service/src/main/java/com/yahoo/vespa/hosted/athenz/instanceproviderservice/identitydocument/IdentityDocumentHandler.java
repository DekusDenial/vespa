// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.athenz.instanceproviderservice.identitydocument;

import com.google.inject.Inject;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.restapi.RestApi;
import com.yahoo.restapi.RestApiRequestHandler;
import com.yahoo.vespa.athenz.identityprovider.api.EntityBindingsMapper;
import com.yahoo.vespa.athenz.identityprovider.api.IdentityType;
import com.yahoo.vespa.athenz.identityprovider.api.bindings.SignedIdentityDocumentEntity;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An API that issues signed identity documents for Vespa nodes.
 *
 * @author bjorncs
 */
public class IdentityDocumentHandler extends RestApiRequestHandler<IdentityDocumentHandler> {

    private static final Logger log = Logger.getLogger(IdentityDocumentHandler.class.getName());

    private final IdentityDocumentGenerator identityDocumentGenerator;

    @Inject
    public IdentityDocumentHandler(LoggingRequestHandler.Context context,
                                   IdentityDocumentGenerator identityDocumentGenerator) {
        super(context, self -> new RestApi.Builder()
                .addRoute(new RestApi.Route.Builder("/identity-document/node/{host}")
                        .get(self::getNodeIdentityDocument)
                        .build())
                .addRoute(new RestApi.Route.Builder("/identity-document/tenant/{host}")
                        .get(self::getTenantIdentityDocument)
                        .build())
                .build());
        this.identityDocumentGenerator = identityDocumentGenerator;
    }

    private SignedIdentityDocumentEntity getIdentityDocument(String hostname, IdentityType identityType) {
        if (hostname == null) {
            throw new RestApi.BadRequestException("The 'hostname' query parameter is missing");
        }
        try {
            return EntityBindingsMapper.toSignedIdentityDocumentEntity(identityDocumentGenerator.generateSignedIdentityDocument(hostname, identityType));
        } catch (Exception e) {
            String message = String.format("Unable to generate identity document for '%s': %s", hostname, e.getMessage());
            log.log(Level.SEVERE, message, e);
            throw new RestApi.InternalServerErrorException(message, e);
        }
    }

    private SignedIdentityDocumentEntity getNodeIdentityDocument(RestApi.RequestContext context) {
        String host = context.pathParameters().getString("host").orElse(null);
        return getIdentityDocument(host, IdentityType.NODE);
    }

    private SignedIdentityDocumentEntity getTenantIdentityDocument(RestApi.RequestContext context) {
        String host = context.pathParameters().getString("host").orElse(null);
        return getIdentityDocument(host, IdentityType.TENANT);
    }

}
