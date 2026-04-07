package org.programmers.signalbuddyfinal.domain.like.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.programmers.signalbuddyfinal.domain.crossroad.entity.Crossroad;
import org.programmers.signalbuddyfinal.domain.crossroad.repository.CrossroadRepository;
import org.programmers.signalbuddyfinal.domain.feedback.entity.Feedback;
import org.programmers.signalbuddyfinal.domain.feedback.entity.enums.FeedbackCategory;
import org.programmers.signalbuddyfinal.domain.feedback.repository.FeedbackRepository;
import org.programmers.signalbuddyfinal.domain.like.service.LikeService;
import org.programmers.signalbuddyfinal.domain.member.entity.Member;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberRole;
import org.programmers.signalbuddyfinal.domain.member.entity.enums.MemberStatus;
import org.programmers.signalbuddyfinal.domain.member.fixture.TestMemberFactory;
import org.programmers.signalbuddyfinal.domain.member.repository.MemberRepository;
import org.programmers.signalbuddyfinal.global.db.RedisTestContainer;
import org.programmers.signalbuddyfinal.global.dto.CustomUser2Member;
import org.programmers.signalbuddyfinal.global.security.basic.CustomUserDetails;
import org.programmers.signalbuddyfinal.global.support.BatchTest;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;

class LikeJobConfigTest extends BatchTest implements RedisTestContainer {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private Job likeRequestJob;

    @Autowired
    private LikeService likeService;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CrossroadRepository crossroadRepository;

    private List<Member> savedMemberList;

    @BeforeEach
    void setup() {
        List<Member> memberList = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            memberList.add(
                TestMemberFactory.createActiveUser("test" + i + "@test.com", "tester" + i)
            );
        }
        savedMemberList = memberRepository.saveAll(memberList);
    }

    @DisplayName("동시에 많은 좋아요 추가/취소 요청이 발생할 때, 좋아요 개수의 정합성이 잘 맞는지 확인한다.")
    @Test
    void likeRequestJob() throws Exception {
        // given
        int addLikeThreadCount = savedMemberList.size(); // 좋아요 추가 요청의 스레드 개수
        ExecutorService executorService = Executors.newFixedThreadPool(addLikeThreadCount);    // 스레드 풀 생성
        final CountDownLatch latch = new CountDownLatch(addLikeThreadCount); // 스레드 대기 관리
        int deleteLikeThreadCount = (int) (addLikeThreadCount * 0.3);   // 좋아요 취소 요청의 스레드 개수
        final CountDownLatch latch2 = new CountDownLatch(deleteLikeThreadCount); // 스레드 대기 관리
        ExecutorService executorService2 = Executors.newFixedThreadPool(deleteLikeThreadCount);    // 스레드 풀 생성

        Crossroad crossroad = Crossroad.create()
            .crossroadApiId("13214").name("00사거리")
            .lat(37.12222).lng(127.12132)
            .build();
        crossroad = crossroadRepository.save(crossroad);

        String subject = "test subject";
        String content = "test content";
        Feedback entity = Feedback.create()
            .subject(subject).content(content).secret(Boolean.FALSE)
            .category(FeedbackCategory.ETC)
            .member(savedMemberList.get(0)).crossroad(crossroad)
            .build();
        Feedback savedFeedback = feedbackRepository.saveAndFlush(entity);

        // when
        // 좋아요 추가
        for (int i = 0; i < addLikeThreadCount; i++) {
            Member member = savedMemberList.get(i);
            executorService.execute(() -> {
                try {
                    CustomUser2Member user = new CustomUser2Member(
                        new CustomUserDetails(member.getMemberId(), "", "",
                            "", "", MemberRole.USER, MemberStatus.ACTIVITY));

                    likeService.addLike(savedFeedback.getFeedbackId(), user);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // 스레드가 다 끝날 때까지 기다리기
        latch.await();
        executorService.shutdown();

        // 좋아요 취소
        for (int i = 0; i < deleteLikeThreadCount; i++) {
            Member member = savedMemberList.get(i);
            executorService2.execute(() -> {
                try {
                    CustomUser2Member user = new CustomUser2Member(
                        new CustomUserDetails(member.getMemberId(), "", "",
                            "", "", MemberRole.USER, MemberStatus.ACTIVITY));

                    likeService.deleteLike(savedFeedback.getFeedbackId(), user);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch2.countDown();
                }
            });
        }

        // 스레드가 다 끝날 때까지 기다리기
        latch2.await();
        executorService2.shutdown();

        jobLauncherTestUtils.setJob(likeRequestJob);
        JobExecution jobExecution = jobLauncherTestUtils.launchJob();

        // then
        Feedback updatedFeedback = feedbackRepository.findById(savedFeedback.getFeedbackId()).get();
        SoftAssertions.assertSoftly(softAssertions -> {
            softAssertions.assertThat(jobExecution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            softAssertions.assertThat(updatedFeedback.getLikeCount())
                .isEqualTo(addLikeThreadCount - deleteLikeThreadCount);
        });
    }
}