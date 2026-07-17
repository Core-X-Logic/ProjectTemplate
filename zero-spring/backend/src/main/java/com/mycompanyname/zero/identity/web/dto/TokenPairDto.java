package com.mycompanyname.zero.identity.web.dto;

public record TokenPairDto(
        String accessToken,
        String refreshToken,
        long expiresInSeconds) {
}
