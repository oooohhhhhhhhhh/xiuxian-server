package com.mtxgdn.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.EOFException;

@Provider
public class EofExceptionMapper implements ExceptionMapper<EOFException> {

    @Override
    public Response toResponse(EOFException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"code\":400,\"message\":\"请求体为空\"}")
                .build();
    }
}
