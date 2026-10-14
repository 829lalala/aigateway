package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.dto.LoginRequest;
import com.aigateway.dto.RegisterRequest;
import com.aigateway.entity.User;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户业务层：注册、登录等业务逻辑
 *
 * @Service：注册为 Spring Bean，被 Controller 注入
 * @RequiredArgsConstructor：为 final 字段生成构造器，Spring 自动完成构造器注入
 * @Slf4j：自动生成日志对象 log（底层是 SLF4J 门面）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** 数据访问接口（构造器注入，final 保证不可变） */
    private final UserMapper userMapper;

    /** BCrypt 加密器（来自 AppConfig 的 @Bean） */
    private final BCryptPasswordEncoder passwordEncoder;

    /** JWT 工具类 */
    private final JwtUtil jwtUtil;

    /**
     * 注册用户
     * 注意：不先查再插做"判重"——并发下两个请求可能同时查到"不存在"再同时插入
     * （TOCTOU 竞态），唯一索引才是可靠的防线，这里只捕获唯一索引冲突异常
     *
     * @return 新用户 ID（Service 只返回业务数据，错误统一抛 BizException）
     */
    public Long register(RegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        // BCrypt 加密：encode 每次生成随机盐，同一密码两次加密的密文不同（这是设计而非 bug）
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole(0);      // 默认普通用户
        user.setBalance(0L);  // 默认余额 0

        try {
            // 插入后 MyBatis-Plus 自动把数据库生成的自增 ID 回填到 user.id
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // Spring 把 MySQL 唯一索引冲突(1062)翻译成了 DuplicateKeyException
            log.warn("注册冲突: username={}, email={}", request.getUsername(), request.getEmail());
            throw new BizException(400, "用户名或邮箱已被注册");
        }

        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    /**
     * 登录：验证用户名密码，签发 JWT
     *
     * 安全细节：用户不存在与密码错误返回同一句提示——
     * 若区分提示，攻击者可借此枚举出"哪些用户名存在"（用户枚举攻击）
     */
    public Map<String, Object> login(LoginRequest request) {
        // 1. 按用户名查用户（LambdaQueryWrapper 用方法引用指定列，编译期防拼写错误）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );

        // 2. 用户不存在 或 密码不匹配：同一句提示（注意 matches 比对的是密文）
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败: username={}", request.getUsername());
            throw new BizException(401, "用户名或密码错误");
        }

        // 3. 签发 JWT
        String token = jwtUtil.createToken(user.getId(), user.getUsername());

        // 4. 返回令牌 + 基础用户信息（密码字段绝不返回）
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        log.info("用户登录成功: id={}", user.getId());
        return data;
    }
}
