package com.educfin.application.port.out;

import com.educfin.application.dto.AuthTokens;
import com.educfin.domain.user.User;
import com.educfin.domain.user.UserId;

/**
 * Porta de saída para emissão e validação de tokens JWT.
 */
public interface TokenProvider {

    AuthTokens issueTokens(User user);

    UserId validateRefreshToken(String refreshToken);
}
