package com.ghyinc.finance.domain.auth.security;

import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String mobile) throws UsernameNotFoundException {
        Member member = memberRepository.findByMobile(mobile)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 계정입니다"));
        return new CustomUserDetails(member);
    }
}
