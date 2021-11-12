package org.acme;

import org.eclipse.microprofile.openapi.annotations.Components;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@OpenAPIDefinition(
        tags = {
                @Tag(name = "document loader", description = "Document Loader Service")
        },
        info = @Info(
                title = "Document Loader API",
                version = "0.0.1",
                contact = @Contact(
                        name = "Document Loader Support",
                        url = "https://document.loader.app/contact",
                        email = "techsupport@document.loader.app"),
                license = @License(
                        name = "Apache 2.0",
                        url = "http://www.apache.org/licenses/LICENSE-2.0.html")),
        components = @Components(
                securitySchemes = {
                        @SecurityScheme(
                                securitySchemeName = "bearerAuth",
                                type = SecuritySchemeType.HTTP,
                                scheme = "bearer",
                                bearerFormat = "JWT"
                        )
                }
        ),
        security = {
                @SecurityRequirement(name = "bearerAuth")
        }
)
public class DocumentLoaderApi {
}
