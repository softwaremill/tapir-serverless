package com.softwaremill.ts.model

case class AwsRequest(
    rawPath: String,
    rawQueryString: String,
    headers: Map[String, String],
    requestContext: AwsRequestContext,
    body: String,
    isBase64Encoded: Boolean
)
case class AwsRequestContext(domainName: String, http: AwsHttp)
case class AwsHttp(method: String, path: String, protocol: String, sourceIp: String, userAgent: String)

case class AwsResponse(cookies: List[String], isBase64Encoded: Boolean, statusCode: Int, headers: Map[String, String], body: String)
