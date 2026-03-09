package com.lostark.invenchecker.controller;

import com.lostark.invenchecker.model.BoardPost;
import com.lostark.invenchecker.service.InvenSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    InvenSearchService searchService;

    @Test
    @DisplayName("정상 닉네임 - 결과 반환")
    void search_validNickname_returnsResults() throws Exception {
        BoardPost post = new BoardPost("테스트 제목", "https://www.inven.co.kr/board/1", "작성자", "2024-01-01", "100", "5");
        given(searchService.search("테스트닉")).willReturn(List.of(post));

        mockMvc.perform(get("/api/search").param("nickname", "테스트닉"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("테스트 제목"))
                .andExpect(jsonPath("$[0].author").value("작성자"))
                .andExpect(jsonPath("$[0].link").value("https://www.inven.co.kr/board/1"));
    }

    @Test
    @DisplayName("정상 닉네임 - 결과 없음")
    void search_validNickname_returnsEmptyList() throws Exception {
        given(searchService.search("없는닉네임")).willReturn(List.of());

        mockMvc.perform(get("/api/search").param("nickname", "없는닉네임"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("닉네임 1글자 - 400 오류")
    void search_tooShortNickname_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("nickname", "가"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("닉네임은 2~12글자여야 합니다."));
    }

    @Test
    @DisplayName("닉네임 13글자 - 400 오류")
    void search_tooLongNickname_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("nickname", "가나다라마바사아자차카타파"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("닉네임은 2~12글자여야 합니다."));
    }

    @Test
    @DisplayName("특수문자 닉네임 - 400 오류")
    void search_specialCharNickname_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("nickname", "닉네임!@#"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("닉네임은 한글, 영문, 숫자만 허용됩니다."));
    }

    @Test
    @DisplayName("공백만 있는 닉네임 - 400 오류")
    void search_blankNickname_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("nickname", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("닉네임을 입력해주세요."));
    }

    @Test
    @DisplayName("서비스 IOException - 500 오류")
    void search_serviceThrowsIOException_returns500() throws Exception {
        given(searchService.search(anyString())).willThrow(new IOException("연결 실패"));

        mockMvc.perform(get("/api/search").param("nickname", "정상닉네임"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("연결 실패")));
    }

    @Test
    @DisplayName("영문+숫자 혼합 닉네임 허용 (예: BeeA23)")
    void search_alphanumericNickname_returnsOk() throws Exception {
        given(searchService.search("BeeA23")).willReturn(List.of());

        mockMvc.perform(get("/api/search").param("nickname", "BeeA23"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("닉네임 파라미터 누락 - 400 오류")
    void search_missingNicknameParam_returns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest());
    }
}
