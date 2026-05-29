package com.myxcomp.ice.xtree.acceptance.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/** Thin RestAssured wrapper: injects base URI, API base path, and the X-Ice-User header. */
public class ApiClient {

    private static final String API_BASE = "/api/v1/itemtree";

    private final String baseUrl;
    private final String user;

    public ApiClient(String baseUrl, String user) {
        this.baseUrl = baseUrl;
        this.user = user;
    }

    private RequestSpecification req() {
        return RestAssured.given()
                .baseUri(baseUrl)
                .basePath(API_BASE)
                .header("X-Ice-User", user)
                .contentType(ContentType.JSON)
                .accept("application/json, application/problem+json");
    }

    public Response post(String path, Object body) {
        return req().body(body).post(path);
    }

    public Response get(String path) {
        return req().get(path);
    }

    public Response getQuery(String path, Map<String, ?> queryParams) {
        return req().queryParams(queryParams).get(path);
    }

    public Response delete(String path) {
        return req().delete(path);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String user() {
        return user;
    }
}
