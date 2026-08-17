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
 * 공고 버전에 속한 공고문과 단지 이미지 파일이다.
 */
@Entity
@Table(name = "notice_attachment",
		uniqueConstraints = @UniqueConstraint(name = "uk_notice_attachment_order",
				columnNames = { "notice_id", "display_order" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeAttachment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notice_id", nullable = false)
	private Notice notice;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(nullable = false, length = 50)
	private String kind;

	@Column(nullable = false, length = 300)
	private String name;

	@Column(nullable = false, length = 500)
	private String url;

	@Column(name = "complex_label", length = 200)
	private String complexLabel;

	NoticeAttachment(Notice notice, int displayOrder, String kind, String name, String url, String complexLabel) {
		this.notice = notice;
		this.displayOrder = displayOrder;
		this.kind = kind;
		this.name = name;
		this.url = url;
		this.complexLabel = complexLabel;
	}

}
