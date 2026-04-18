package org.programmers.signalbuddyfinal.domain.crossroad.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.programmers.signalbuddyfinal.domain.crossroad.dto.CrossroadApiResponse;
import org.programmers.signalbuddyfinal.domain.crossroad.dto.CrossroadStateApiResponse;
import org.programmers.signalbuddyfinal.domain.crossroad.dto.CrossroadStateResponse;
import org.programmers.signalbuddyfinal.domain.crossroad.dto.SignalState;
import org.programmers.signalbuddyfinal.domain.crossroad.entity.Crossroad;
import org.programmers.signalbuddyfinal.domain.crossroad.exception.CrossroadErrorCode;
import org.programmers.signalbuddyfinal.domain.crossroad.repository.CrossroadRepository;
import org.programmers.signalbuddyfinal.global.exception.BusinessException;
import org.programmers.signalbuddyfinal.global.monitoring.HttpRequestManager;
import org.programmers.signalbuddyfinal.global.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CrossroadServiceTest extends IntegrationTest {

    @Autowired
    private CrossroadService crossroadService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private CrossroadRepository crossroadRepository;

    @MockitoBean
    private CrossroadProvider crossroadProvider;

    @MockitoBean
    private HttpRequestManager httpRequestManager;

    private Crossroad crossroad;

    @BeforeEach
    void setUp() {
        crossroad = saveCrossroad("13214", "00사거리", 37.12222, 127.12132);
    }

    @DisplayName("외부 API를 호출하여 교차로 데이터를 저장한다.")
    @Test
    void saveCrossroadData_Success() {
        // Given
        int page = 2;
        int pageSize = 10;

        List<CrossroadApiResponse> responseList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responseList.add(
                CrossroadApiResponse.builder()
                    .crossroadApiId(String.valueOf(i))
                    .name("00 교차로 - " + i)
                    .lat(37.1010 + i)
                    .lng(127.1020 + i)
                    .build()
            );
        }

        when(crossroadProvider.requestCrossroadApi(page, pageSize))
            .thenReturn(responseList);

        // When
        crossroadService.saveCrossroadData(page, pageSize);

        // Then
        Crossroad actual = crossroadRepository.findByIdOrThrow(5L);
        SoftAssertions.assertSoftly(softAssertions -> {
            softAssertions.assertThat(actual.getCoordinate()).isNotNull();
            softAssertions.assertThat(actual.getName()).contains("00 교차로 - ");
        });
    }

    @DisplayName("교차로 데이터가 중복 저장되어 실패한다.")
    @Test
    void saveCrossroadData_Failure() {
        // Given
        int page = 2;
        int pageSize = 10;

        List<CrossroadApiResponse> responseList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responseList.add(
                CrossroadApiResponse.builder()
                    .crossroadApiId(String.valueOf(10))
                    .name("00 교차로 - " + i)
                    .lat(37.1010 + i)
                    .lng(127.1020 + i)
                    .build()
            );
        }

        when(crossroadProvider.requestCrossroadApi(page, pageSize))
            .thenReturn(responseList);

        // When & Then
        try {
            crossroadService.saveCrossroadData(page, pageSize);
        } catch (BusinessException e) {
            assertThat(e.getErrorCode())
                .isEqualTo(CrossroadErrorCode.ALREADY_EXIST_CROSSROAD);
        }
    }

    @DisplayName("신호등 잔여시간 정보를 Redis에 캐싱한 뒤 반환한다.")
    @Test
    void checkSignalState() {
        // Given
        Long crossroadId = crossroad.getCrossroadId();
        String crossroadApiId = crossroad.getCrossroadApiId();
        CrossroadStateApiResponse apiResponse = CrossroadStateApiResponse.builder()
            .crossroadApiId(crossroadApiId).transTimestamp(1741054218628L)
            .northTimeLeft(349).northState(SignalState.RED)
            .eastTimeLeft(9).eastState(SignalState.YELLOW)
            .southTimeLeft(349).eastState(SignalState.RED)
            .build();

        given(crossroadProvider.requestCrossroadStateApi(crossroadApiId))
            .willReturn(List.of(apiResponse));

        // When
        CrossroadStateResponse actual = crossroadService.checkSignalState(crossroadId);

        // Then
        CrossroadStateResponse cache = (CrossroadStateResponse) redisTemplate.opsForValue()
            .get("crossroad-state:" + crossroadId);
        SoftAssertions.assertSoftly(softAssertions -> {
           softAssertions.assertThat(actual).isNotNull();
           softAssertions.assertThat(actual.getCrossroadId()).isEqualTo(crossroadId);
           softAssertions.assertThat(actual.getTransTimestamp()).isEqualTo(1741054218628L);
           softAssertions.assertThat(actual.getNorthState()).isEqualTo(SignalState.RED);
           softAssertions.assertThat(actual.getEastTimeLeft()).isEqualTo(9);
           softAssertions.assertThat(cache).isNotNull();
           softAssertions.assertThat(actual.getSouthTimeLeft()).isEqualTo(cache.getSouthTimeLeft());
        });
    }

    @DisplayName("캐싱된 신호등 잔여시간 정보를 바로 반환한다.")
    @Test
    void checkSignalState_Cache() {
        // Given
        Long crossroadId = crossroad.getCrossroadId();
        String crossroadApiId = crossroad.getCrossroadApiId();
        CrossroadStateApiResponse apiResponse = CrossroadStateApiResponse.builder()
            .crossroadApiId(crossroadApiId).transTimestamp(1741054218628L)
            .northTimeLeft(349).northState(SignalState.RED)
            .eastTimeLeft(9).eastState(SignalState.YELLOW)
            .southTimeLeft(349).eastState(SignalState.RED)
            .build();

        given(crossroadProvider.requestCrossroadStateApi(crossroadApiId))
            .willReturn(List.of(apiResponse));

        // When
        CrossroadStateResponse actual = crossroadService.checkSignalState(crossroadId);
        CrossroadStateResponse cache = crossroadService.checkSignalState(crossroadId);

        // Then
        assertThat(actual).isEqualTo(cache);
    }

    private Crossroad saveCrossroad(String apiId, String name, double lat, double lng) {
        return crossroadRepository.save(
            Crossroad.create()
                .crossroadApiId(apiId).name(name)
                .lat(lat).lng(lng)
                .build()
        );
    }
}