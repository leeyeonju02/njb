package njb.recipe.service;

import lombok.RequiredArgsConstructor;
import njb.recipe.dto.member.MemberRequestDTO;
import njb.recipe.dto.member.MemberResponseDTO;
import njb.recipe.dto.member.SignupRequestDTO;
import njb.recipe.dto.token.TokenResponseDTO;
import njb.recipe.dto.token.TokenRequestDTO;
import njb.recipe.entity.ActivationToken;
import njb.recipe.entity.Member;
import njb.recipe.global.jwt.TokenProvider;
import njb.recipe.handler.exception.DuplicateEmailException;
import njb.recipe.repository.ActivationTokenRepository;
import njb.recipe.repository.MemberRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {
    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final ActivationTokenRepository activationTokenRepository;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final AuthenticationManager authenticationManager;
    private final TokenProvider tokenProvider;
    private final EmailService emailService;


    public MemberResponseDTO signup(MemberRequestDTO memberRequestDTO){
        memberRepository.findByEmail(memberRequestDTO.getEmail())
                .ifPresent(member -> {
                    throw new RuntimeException("이미 가입되어 있는 유저입니다.");
                });

        Member member = memberRequestDTO.toEntity(passwordEncoder);
        member.activate();
        return MemberResponseDTO.of(memberRepository.save(member));

    }

    public TokenResponseDTO login(MemberRequestDTO memberRequestDTO, String ua){
        //1. Login ID/PW 기반으로 AuthenticationToken 생성
        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(memberRequestDTO.getEmail(), memberRequestDTO.getPassword());
        Authentication authenticate = authenticationManager.authenticate(authenticationToken);


        Member member = memberRepository.findByEmail(authenticate.getName())
                .orElseThrow(() -> new RuntimeException("가입되지 않은 유저입니다."));


        TokenResponseDTO tokenResponseDTO = tokenProvider.generateAccessTokenAndRefreshToken(member, ua, memberRequestDTO.getAutoLogin());

        return tokenResponseDTO;
    }

    public TokenResponseDTO reissue(TokenRequestDTO tokenRequestDTO, String ua){

        // 1. Refresh Token 검증
        if(!tokenProvider.validateToken(tokenRequestDTO.getRefreshToken())){
            throw new RuntimeException("Refresh Token이 유효하지 않습니다.");
        }

        // 2. Access Token 에서 Member Email 가져오기
        long memberId = Long.parseLong(tokenProvider.getID(tokenRequestDTO.getRefreshToken()));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("가입되지 않은 유저입니다."));
        String accessToken = tokenProvider.generateAccessToken(member);
        // 4. Refresh Token 일치하는 지 검사

        // 5. 새로운 토큰 생성

        // 6. 저장소 정보 업데이트

        String refreshToken = tokenProvider.updateRefreshToken(tokenRequestDTO.getRefreshToken(),memberId);

        return TokenResponseDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }


    public void registerUser(SignupRequestDTO signupRequestDTO) {
        memberRepository.findByEmail(signupRequestDTO.getEmail())
                .ifPresent(member -> {
                    throw new DuplicateEmailException("Duplicated Email.");
                });

        Member member = signupRequestDTO.toEntity(passwordEncoder);

        String activationToken = UUID.randomUUID().toString();

        ActivationToken token = ActivationToken.builder()
                .token(activationToken)
                .createdAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(30))
                .member(member)
                .build();

        activationTokenRepository.save(token);

        emailService.sendEmail(signupRequestDTO.getEmail(),activationToken);
    }

    public void activateUser(String value) {
        ActivationToken token = activationTokenRepository.findByToken(value)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

        if(token.getExpiredAt().isBefore(LocalDateTime.now())){
            throw new IllegalArgumentException("만료된 토큰입니다.");
        }

        token.getMember().activate();
        memberRepository.save(token.getMember());


        activationTokenRepository.delete(token);


    }

    public boolean isAutoLogin(String refreshToken){
        return tokenProvider.isAutoLogin(refreshToken);
    }

    public boolean checkEmail(String email) {
        return memberRepository.findByEmail(email).isPresent();
    }
}
