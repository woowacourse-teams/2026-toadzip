package com.toadzip.backend.notice;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고 버전에 속한 서류 제출과 계약 일정이다.
 */
@Entity
@Table(name = "notice_schedule",
		uniqueConstraints = @UniqueConstraint(name = "uk_notice_schedule_order",
				columnNames = { "notice_id", "display_order" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notice_id", nullable = false)
	private Notice notice;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "complex_label", length = 200)
	private String complexLabel;

	@Column(name = "application_period_text", length = 300)
	private String applicationPeriodText;

	@Column(name = "document_target_announced_on")
	private LocalDate documentTargetAnnouncedOn;

	@Column(name = "document_submission_begin_on")
	private LocalDate documentSubmissionBeginOn;

	@Column(name = "document_submission_end_on")
	private LocalDate documentSubmissionEndOn;

	@Column(name = "contract_begin_on")
	private LocalDate contractBeginOn;

	@Column(name = "contract_end_on")
	private LocalDate contractEndOn;

	NoticeSchedule(Notice notice, int displayOrder, String complexLabel, String applicationPeriodText,
			LocalDate documentTargetAnnouncedOn, LocalDate documentSubmissionBeginOn, LocalDate documentSubmissionEndOn,
			LocalDate contractBeginOn, LocalDate contractEndOn) {
		this.notice = notice;
		this.displayOrder = displayOrder;
		this.complexLabel = complexLabel;
		this.applicationPeriodText = applicationPeriodText;
		this.documentTargetAnnouncedOn = documentTargetAnnouncedOn;
		this.documentSubmissionBeginOn = documentSubmissionBeginOn;
		this.documentSubmissionEndOn = documentSubmissionEndOn;
		this.contractBeginOn = contractBeginOn;
		this.contractEndOn = contractEndOn;
	}

}
