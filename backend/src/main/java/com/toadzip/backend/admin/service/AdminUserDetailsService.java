package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminAccountRepository adminAccountRepository;

    @Override
    public UserDetails loadUserByUsername(String loginIdentifier) {
        AdminAccount adminAccount = adminAccountRepository.findByLoginIdentifier(loginIdentifier)
                .orElseThrow(() -> new UsernameNotFoundException("관리자 계정을 찾을 수 없습니다."));

        return org.springframework.security.core.userdetails.User.withUsername(adminAccount.getLoginIdentifier())
                .password(adminAccount.getPasswordHash())
                .roles(adminAccount.getRole().name())
                .build();
    }
}
