package com.arjun.tracer.resolve;

/**
 * A REST endpoint discovered in a controller source file.
 *
 * @param path           full request path, e.g. {@code /payment/v2/fund/submit}
 * @param operationName  the handler method name = the Camel operation name,
 *                       e.g. {@code fundTransferSubmitV2Api}
 * @param command        the UFW command bound via {@code @CommandHandler}, if any
 *                       (null when {@code @CommandHandler} is a bare marker)
 * @param httpMethod     GET / POST / ... (informational)
 * @param controllerType the declaring class simple name (informational)
 * @param commandHandler true if the handler carries {@code @CommandHandler} (the UFW
 *                       form), regardless of whether it names a command — distinguishes
 *                       a migrated UFW endpoint from a legacy BAU one
 */
public record OperationInfo(String path, String operationName, String command,
                            String httpMethod, String controllerType, boolean commandHandler) {
}
