package com.hmall.user.domain.vo;

import lombok.Data;

@Data
public class UserLoginVO {
    private String token;
    private Long userId;
    private String username;
    //账户余额
    private Integer balance;
}
