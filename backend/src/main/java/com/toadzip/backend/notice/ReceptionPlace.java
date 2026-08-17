package com.toadzip.backend.notice;

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
 * 공고 버전에 속한 현장 접수처다.
 */
@Entity
@Table(name = "reception_place",
		uniqueConstraints = @UniqueConstraint(name = "uk_reception_place_order",
				columnNames = { "notice_id", "display_order" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReceptionPlace {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notice_id", nullable = false)
	private Notice notice;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(length = 300)
	private String address;

	@Column(name = "detail_address", length = 300)
	private String detailAddress;

	@Column(name = "operation_begin_text", length = 100)
	private String operationBeginText;

	@Column(name = "operation_end_text", length = 100)
	private String operationEndText;

	@Column(length = 100)
	private String phone;

	@Column(length = 4000)
	private String guidance;

	ReceptionPlace(Notice notice, int displayOrder, String address, String detailAddress, String operationBeginText,
			String operationEndText, String phone, String guidance) {
		this.notice = notice;
		this.displayOrder = displayOrder;
		this.address = address;
		this.detailAddress = detailAddress;
		this.operationBeginText = operationBeginText;
		this.operationEndText = operationEndText;
		this.phone = phone;
		this.guidance = guidance;
	}

}
