package com.toadzip.backend.notice;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원천 공고 식별자 하나에 대응하는 공고 버전이다.
 *
 * <p>
 * 이전 버전 참조와 루트 식별자로 정정 공고의 버전 체인을 관리한다.
 */
@Entity
@Table(name = "notice",
		uniqueConstraints = @UniqueConstraint(name = "uk_notice_source", columnNames = "source_notice_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_notice_id", nullable = false, length = 50)
	private String sourceNoticeId;

	@Column(name = "before_source_notice_id", length = 50)
	private String beforeSourceNoticeId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "supersedes_notice_id")
	private Notice supersedesNotice;

	@Column(name = "root_source_notice_id", nullable = false, length = 50)
	private String rootSourceNoticeId;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(name = "notice_change_status_name", length = 30)
	private String noticeChangeStatusName;

	private LocalDateTime publishedAt;

	@Column(nullable = false)
	private String title;

	@Column(name = "detail_url", length = 500)
	private String detailUrl;

	@Column(name = "application_begin_on")
	private LocalDate applicationBeginOn;

	@Column(name = "application_end_on")
	private LocalDate applicationEndOn;

	@Column(name = "winner_announced_on")
	private LocalDate winnerAnnouncedOn;

	@Column(name = "supply_institution_name", length = 50)
	private String supplyInstitutionName;

	@Column(name = "house_type_name", length = 30)
	private String houseTypeName;

	@Column(name = "supply_type_name", length = 30)
	private String supplyTypeName;

	@Column(name = "contact", length = 200)
	private String contact;

	@Column(name = "source_pan_id", length = 50)
	private String sourcePanId;

	@Column(name = "lh_supply_info_type_code", length = 10)
	private String lhSupplyInfoTypeCode;

	/**
	 * 마지막 LH 조회 완료 시각이다.
	 */
	@Column(name = "lh_fetched_at")
	private LocalDateTime lhFetchedAt;

	@Column(name = "correction_reason", length = 2000)
	private String correctionReason;

	@OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder ASC")
	private List<NoticeSchedule> schedules = new ArrayList<>();

	@OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder ASC")
	private List<ReceptionPlace> receptionPlaces = new ArrayList<>();

	@OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder ASC")
	private List<NoticeAttachment> attachments = new ArrayList<>();

	private Notice(String sourceNoticeId, String beforeSourceNoticeId, Notice supersedesNotice,
			String rootSourceNoticeId, int versionNumber, NoticeSnapshot snapshot) {
		this.sourceNoticeId = sourceNoticeId;
		this.beforeSourceNoticeId = beforeSourceNoticeId;
		this.supersedesNotice = supersedesNotice;
		this.rootSourceNoticeId = rootSourceNoticeId;
		this.versionNumber = versionNumber;
		applySnapshot(snapshot);
	}

	/**
	 * 자신을 루트로 하는 첫 공고 버전을 만든다.
	 */
	public static Notice firstVersion(String sourceNoticeId, String beforeSourceNoticeId, NoticeSnapshot snapshot) {
		return new Notice(sourceNoticeId, beforeSourceNoticeId, null, sourceNoticeId, 1, snapshot);
	}

	/**
	 * 현재 버전 뒤에 이어지는 새 공고 버전을 만든다.
	 */
	public Notice nextVersion(String sourceNoticeId, String beforeSourceNoticeId, NoticeSnapshot snapshot) {
		return new Notice(sourceNoticeId, beforeSourceNoticeId, this, this.rootSourceNoticeId, this.versionNumber + 1,
				snapshot);
	}

	/**
	 * 현재 버전을 이전 공고의 다음 버전으로 연결한다.
	 */
	public void rebaseOnto(Notice previous) {
		this.supersedesNotice = previous;
		this.rootSourceNoticeId = previous.rootSourceNoticeId;
		this.versionNumber = previous.versionNumber + 1;
	}

	/**
	 * 현재 공고 내용과 주어진 스냅샷을 비교한다.
	 */
	public boolean hasSameContentAs(NoticeSnapshot snapshot) {
		return Objects.equals(currentSnapshot(), snapshot);
	}

	public NoticeSnapshot currentSnapshot() {
		return new NoticeSnapshot(noticeChangeStatusName, publishedAt, title, detailUrl, applicationBeginOn,
				applicationEndOn, winnerAnnouncedOn, supplyInstitutionName, houseTypeName, supplyTypeName, contact);
	}

	/**
	 * LH 상세 조회에 필요한 식별자를 기록한다.
	 */
	public void prepareLhRequest(String sourcePanId, String lhSupplyInfoTypeCode) {
		this.sourcePanId = sourcePanId;
		this.lhSupplyInfoTypeCode = lhSupplyInfoTypeCode;
	}

	/**
	 * LH 조회 결과와 완료 시각을 기록한다.
	 */
	public void markLhFetched(String sourcePanId, String lhSupplyInfoTypeCode, String correctionReason,
			LocalDateTime fetchedAt) {
		this.sourcePanId = sourcePanId;
		this.lhSupplyInfoTypeCode = lhSupplyInfoTypeCode;
		this.correctionReason = correctionReason;
		this.lhFetchedAt = fetchedAt;
	}

	/**
	 * 일정, 접수처, 첨부 정보를 모두 비운다.
	 */
	public void clearLhChildren() {
		schedules.clear();
		receptionPlaces.clear();
		attachments.clear();
	}

	public void addSchedule(String complexLabel, String applicationPeriodText, LocalDate documentTargetAnnouncedOn,
			LocalDate documentSubmissionBeginOn, LocalDate documentSubmissionEndOn, LocalDate contractBeginOn,
			LocalDate contractEndOn) {
		schedules.add(new NoticeSchedule(this, schedules.size(), complexLabel, applicationPeriodText,
				documentTargetAnnouncedOn, documentSubmissionBeginOn, documentSubmissionEndOn, contractBeginOn,
				contractEndOn));
	}

	public void addReceptionPlace(String address, String detailAddress, String operationBeginText,
			String operationEndText, String phone, String guidance) {
		receptionPlaces.add(new ReceptionPlace(this, receptionPlaces.size(), address, detailAddress, operationBeginText,
				operationEndText, phone, guidance));
	}

	public void addAttachment(String kind, String name, String url, String complexLabel) {
		attachments.add(new NoticeAttachment(this, attachments.size(), kind, name, url, complexLabel));
	}

	public List<NoticeSchedule> getSchedules() {
		return List.copyOf(schedules);
	}

	public List<ReceptionPlace> getReceptionPlaces() {
		return List.copyOf(receptionPlaces);
	}

	public List<NoticeAttachment> getAttachments() {
		return List.copyOf(attachments);
	}

	private void applySnapshot(NoticeSnapshot snapshot) {
		this.noticeChangeStatusName = snapshot.changeStatusName();
		this.publishedAt = snapshot.publishedAt();
		this.title = snapshot.title();
		this.detailUrl = snapshot.detailUrl();
		this.applicationBeginOn = snapshot.applicationBeginOn();
		this.applicationEndOn = snapshot.applicationEndOn();
		this.winnerAnnouncedOn = snapshot.winnerAnnouncedOn();
		this.supplyInstitutionName = snapshot.supplyInstitutionName();
		this.houseTypeName = snapshot.houseTypeName();
		this.supplyTypeName = snapshot.supplyTypeName();
		this.contact = snapshot.contact();
	}

}
