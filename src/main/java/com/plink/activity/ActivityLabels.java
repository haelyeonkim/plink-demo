package com.plink.activity;

import java.util.LinkedHashMap;
import java.util.Map;

/** The words the console uses for the log's categories and actions. */
public final class ActivityLabels {
    public static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

    static {
        CATEGORIES.put(Activity.ADMISSION, "입장 · 퇴장");
        CATEGORIES.put(Activity.FACE, "얼굴 확인");
        CATEGORIES.put(Activity.TICKET, "발급 · 등록");
        CATEGORIES.put(Activity.DELIVERY, "메일 · 문자");
        CATEGORIES.put(Activity.VIEW, "화면 열람");
        CATEGORIES.put(Activity.INVITE, "초대 · 양도");
        CATEGORIES.put(Activity.BOOTH, "부스 · 쿠폰");
        CATEGORIES.put(Activity.CHAT, "채팅 · 홍보글");
        CATEGORIES.put(Activity.LINK, "링크");
        CATEGORIES.put(Activity.ADMIN, "관리자 작업");
        CATEGORIES.put(Activity.AUTH, "로그인");
        CATEGORIES.put(Activity.REQUEST, "모든 요청");
        CATEGORIES.put(Activity.CLIENT, "브라우저");
        CATEGORIES.put(Activity.ERROR, "오류");

        String[][] actions = {
            {"admission.in", "입장"}, {"admission.out", "퇴장"},
            {"face.enrol", "얼굴 등록"}, {"face.gate", "얼굴 확인"}, {"face.withdraw", "얼굴 삭제"},
            {"face.transfer", "얼굴 삭제 · 양도"}, {"face.consent", "얼굴 동의"}, {"face.template_saved", "얼굴 저장"},
            {"ticket.issued", "발급"}, {"ticket.reissued_by_console", "새 링크 발급"},
            {"ticket.reissued_by_holder", "본인 재발급"}, {"ticket.resent", "링크 다시 보냄"},
            {"ticket.mailed_first", "발급 메일 보냄"}, {"ticket.deleted", "입장권 삭제"},
            {"ticket.revoked", "비활성화"}, {"ticket.restored", "다시 활성화"}, {"ticket.bound", "등록"},
            {"ticket.qr_shown", "QR 띄움"}, {"coupon.qr_shown", "쿠폰 QR 띄움"},
            {"passkey.register", "패스키 등록"}, {"passkey.claim", "패스키로 등록"}, {"passkey.view", "패스키 본인 확인"},
            {"passkey.present", "패스키로 QR"}, {"passkey.transfer", "패스키로 양도"},
            {"mail.send", "메일"}, {"sms.send", "문자"},
            {"ticket.page", "입장권 화면"},
            {"invite.issued", "초대"}, {"invite.resent", "초대 다시 보냄"}, {"invite.renamed", "초대 이름"},
            {"invite.cancelled", "초대 취소"}, {"invite.quota_set", "초대 인원 변경"},
            {"invite.quotas_set", "초대 인원 일괄 변경"},
            {"transfer.requested", "양도 요청"}, {"transfer.accepted", "양도 받음"},
            {"transfer.cancelled", "양도 취소"}, {"transfer.expired", "양도 만료"},
            {"coupon.issued", "쿠폰 지급"}, {"coupon.redeemed", "쿠폰 사용"},
            {"coupon.redeemed_by_staff", "쿠폰 사용 (운영)"}, {"coupon.voided", "쿠폰 취소"},
            {"coupon.restored", "쿠폰 되돌림"}, {"coupon.deleted", "쿠폰 삭제"},
            {"booth.manager_invited", "부스 담당자 초대"}, {"booth.manager_reissued", "담당자 링크 재발급"},
            {"booth.manager_revoked", "담당자 해지"}, {"booth.manager_assigned", "담당 단말 변경"},
            {"booth.terminal_connected", "부스 단말 연결"},
            {"gate.device_bound", "단말 연결"}, {"gate.device_taken_over", "단말 넘겨받음"},
            {"gate.setup_opened", "설정 링크 발급"}, {"gate.setup_completed", "단말 설정 완료"},
            {"chat.message", "채팅"}, {"chat.notice", "공지"}, {"chat.staff_message", "운영팀 채팅"},
            {"chat.like", "좋아요"}, {"chat.dislike", "싫어요"}, {"chat.report", "신고"},
            {"chat.reports_cleared", "신고 정리"}, {"chat.hidden", "채팅 숨김"}, {"chat.unhidden", "숨김 해제"},
            {"chat.pinned", "고정"}, {"chat.unpinned", "고정 해제"}, {"chat.hot_pinned", "인기 고정"},
            {"chat.muted", "채팅 제한"}, {"chat.unmuted", "제한 해제"}, {"chat.banned", "채팅 차단"},
            {"chat.unbanned", "차단 해제"},
            {"ad.created", "홍보글 작성"}, {"ad.review", "홍보글 검토"}, {"ad.hidden", "홍보글 숨김"},
            {"ad.ended", "홍보글 내림"}, {"ad.deleted", "홍보글 삭제"}, {"ad.view", "홍보글 조회"},
            {"link.initial_open", "링크 처음 열림"}, {"link.passkey_authenticated", "링크 패스키 확인"},
            {"link.opened", "링크 열림"},
            {"artwork.interest", "작품 관심 표시"}, {"artwork.uninterest", "작품 관심 해제"},
            {"artwork.request_hold", "작품 홀딩 요청"}, {"artwork.cancel_hold", "작품 홀딩 취소"},
            {"artwork.approve", "작품 홀딩 승인"}, {"artwork.reject", "작품 홀딩 거절"},
            {"artwork.release", "작품 홀딩 해제"}, {"artwork.expired", "작품 홀딩 만료"},
            {"artwork.purchase", "작품 구매 의사"}, {"artwork.withdraw", "작품 구매 의사 철회"},
            {"artwork.sell", "작품 판매 확정"}, {"artwork.request_cancel", "작품 판매 취소 요청"},
            {"artwork.cancel_sale", "작품 판매 취소 승인"}, {"artwork.keep_sale", "작품 판매 취소 거절"},
            {"artwork.closed", "작품 요청 종료"},
            {"admin.login", "관리자 로그인"}, {"google.login", "Google 로그인"},
            {"google.login_failed", "Google 로그인 실패"}, {"logout", "로그아웃"},
            {"otp.sent", "인증번호 보냄"}, {"otp.verify", "인증번호 확인"},
            {"gate.auth", "단말 인증"}, {"gate.setup_code", "단말 설정 코드"},
            {"client.view", "화면 열림"}, {"client.error", "브라우저 오류"}, {"server.error", "서버 오류"},
            {"export.created", "내보내기 만듦"}, {"export.mailed", "내보내기 링크 메일"},
            {"export.closed", "내보내기 닫음"}, {"export.opened", "내보내기 열람"},
            {"export.code_sent", "내보내기 코드 보냄"}, {"export.verified", "내보내기 코드 확인"},
            {"export.download", "내보내기 다운로드"}, {"export.purged", "내보내기 파일 지움"},
        };
        for (String[] pair : actions) ACTIONS.put(pair[0], pair[1]);
    }

    private ActivityLabels() { }

    public static String category(String key) {
        return CATEGORIES.getOrDefault(key, key);
    }

    /** A request row is named by its handler; anything unnamed keeps its key. */
    public static String action(String category, String action) {
        String label = ACTIONS.get(action);
        if (label != null) return label;
        if (Activity.REQUEST.equals(category)) return "요청 · " + action;
        return action;
    }
}
