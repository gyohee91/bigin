package com.ghyinc.finance.domain.user.repository;

import com.ghyinc.finance.domain.user.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByMobile(String mobile);

    Optional<Member> findByCiIn(List<String> ciList);
}
