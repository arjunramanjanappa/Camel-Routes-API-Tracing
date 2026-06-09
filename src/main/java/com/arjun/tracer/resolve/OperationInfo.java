package com.arjun.tracer.resolve;

/**
 * A REST endpoint discovered in a controller source file.
 *
 * @param path           full request path, e.g. {@code /payment/v2/fund/submit}
 * @param operationName  the handler method name = the Camel operation name,
 *                       e.g. {@code fundTransferSubmitV2Api}
 * @param command        the UFW command bound via {@code @CommandHandler}, if any
 * @param httpMethod     GET / POST / ... (informational)
 * @param controllerType the declaring class simple name (informational)
 */
public record OperationInfo(String path, String operationName, String command,
                            String httpMethod, String controllerType) {
}
