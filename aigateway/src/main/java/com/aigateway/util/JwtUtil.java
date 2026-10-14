package com.aigateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：负责令牌的生成与解析
 *
 * 核心认知：JWT 是"签名防篡改"，不是加密！
 *   Header/Payload 仅 Base64URL 编码，任何人可解码阅读，
 *   因此 Payload 里绝不能放密码等敏感信息。
 * 签名的作用：内容被改动一个字节，验签就会失败。
 */
@Component
public class JwtUtil {

    /** 密钥（来自 application.properties 的 jwt.secret） */
    @Value("${jwt.secret}")
    private String secret;

    /** 有效期（毫秒） */
    @Value("${jwt.expire-millis}")
    private long expireMillis;

    /**
     * 生成令牌
     * @param userId   用户 ID（放 subject，即令牌主体）
     * @param username 用户名（放自定义 claim）
     */
    public String createToken(Long userId, String username) {
        // HMAC-SHA256 要求密钥至少 256 位（32 字节），hmacShaKeyFor 会校验
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expireMillis))
                .signWith(key)
                .compact(); // 生成三段式字符串
    }

    /**
     * 解析并验签令牌
     *
     * @return 校验通过返回 Claims（负载）；无效/过期/被篡改返回 null
     *         生产环境建议区分异常类型（ExpiredJwtException 等）做精细化处理
     */
    public Claims parseToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parser()
                    .verifyWith(key)     // 用密钥验签：签名不符抛异常
                    .build()
                    .parseSignedClaims(token) // 同时校验格式与过期时间
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 过期(ExpiredJwtException)/篡改(SignatureException)/格式错(MalformedJwtException)统一视为无效
            return null;
        }
    }
}
