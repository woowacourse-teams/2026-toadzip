export const surveyAnswers = {
  VERY_DISAPPOINTED: '매우 실망',
  SOMEWHAT_DISAPPOINTED: '약간 실망',
  NOT_DISAPPOINTED: '실망하지 않음',
  NO_LONGER_USING: '더 이상 쓰지 않음',
} as const
export type SurveyAnswer = keyof typeof surveyAnswers
