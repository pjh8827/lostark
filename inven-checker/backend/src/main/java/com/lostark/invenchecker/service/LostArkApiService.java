package com.lostark.invenchecker.service;

import com.lostark.invenchecker.model.CharacterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class LostArkApiService {

    private static final Logger log = LoggerFactory.getLogger(LostArkApiService.class);
    private static final String API_BASE = "https://developer-lostark.game.onstove.com";

    private final RestClient restClient;

    public LostArkApiService() {
        this.restClient = RestClient.builder()
                .baseUrl(API_BASE)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * 입력한 캐릭터 닉네임을 기준으로 계정 내 모든 캐릭터 목록을 조회합니다.
     *
     * @param characterName 기준 캐릭터 닉네임
     * @param apiKey        Lost Ark Open API 키 (Bearer 토큰)
     * @return 동일 계정의 캐릭터 목록
     * @throws IllegalArgumentException API 키 인증 실패(401) 또는 캐릭터 없음(404)
     * @throws RuntimeException         API 서버 오류
     */
    public List<CharacterInfo> getSiblings(String characterName, String apiKey) {
        log.info("[LostArk API] 형제 캐릭터 조회: '{}'", characterName);

        CharacterInfo[] result = restClient.get()
                .uri("/characters/{name}/siblings", characterName)
                .header("Authorization", "bearer " + apiKey)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    int status = res.getStatusCode().value();
                    String msg = switch (status) {
                        case 401 -> "API 키가 유효하지 않습니다. (401 Unauthorized)";
                        case 404 -> "캐릭터 '" + characterName + "'를 찾을 수 없습니다. (404 Not Found)";
                        case 429 -> "API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요. (429 Too Many Requests)";
                        default  -> "API 오류가 발생했습니다. (HTTP " + status + ")";
                    };
                    throw new IllegalArgumentException(msg);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new RuntimeException("Lost Ark API 서버 오류입니다. 잠시 후 다시 시도해주세요.");
                })
                .body(CharacterInfo[].class);

        List<CharacterInfo> list = result != null ? List.of(result) : List.of();
        log.info("[LostArk API] 형제 캐릭터 {}개 조회됨", list.size());
        return list;
    }
}
