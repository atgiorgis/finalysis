/**
 * Chat fixtures, hand-written from the Phase 6 response records (`ChatTurnResponse`, `Citation`,
 * `ConversationResponses`): a real turn needs a model, and the fixture capture runs with AI off (docs/plans/phase-7.md
 * §6.1). Figures are the sample data's (September 2026, Groceries; the Safeway duplicate; Spotify's price increase).
 */
import type { CitationDrillDown, CitedTxn, ChatTurn, Conversation, StoredTurn } from '../api'
import { fixtures } from './fixtures'

const TXN = fixtures.transactionDetail

export const citedTxn: CitedTxn = {
  status: 'FOUND',
  fingerprint: 'f6a1c2',
  id: TXN.id,
  accountId: TXN.accountId,
  accountName: TXN.accountName,
  txnDate: TXN.txnDate,
  postDate: TXN.postDate,
  amount: TXN.amount,
  merchant: TXN.merchant,
  category: 'Groceries',
  statement: TXN.statement,
  sourceLine: TXN.sourceLine,
}

export const groceriesDrill: CitationDrillDown = {
  path: '/api/analytics/transactions',
  params: { month: '2026-09', bucket: 'EXPENSE', categoryId: String(fixtures.detailsCategory.category?.id) },
  total: fixtures.drillDownCategory.total,
  count: fixtures.drillDownCategory.total.count,
  sampleTxns: [citedTxn],
}

export const spotifyDrill: CitationDrillDown = {
  path: '/api/analytics/transactions',
  params: { from: '2026-04', to: '2026-09', accountId: '3', merchant: 'SPOTIFY USA', measure: 'SPENT' },
  total: { moneyOut: '-75.94', moneyIn: '0.00', net: '-75.94', count: 6 },
  count: 6,
  sampleTxns: [],
}

const SAFEWAY = fixtures.alertsAll.items[4]

/** A turn citing a transaction, a total, an alert and a series, with figures and markers in emphasis. */
export const richTurn: ChatTurn = {
  conversationId: 7,
  turn: 1,
  answer:
    'You spent **$477.94** on groceries in September [2], including $74.09 at Trader Joe’s [1].\n' +
    '* The duplicate at Safeway is flagged [3].\n' +
    '* Spotify went up to $12.99 [4].\n' +
    'Ignore [9] and [click here](/drill?bucket=EXPENSE&evil=1).',
  answerSource: 'MODEL',
  grounding: { status: 'GROUNDED', figuresChecked: 3, retried: false, ungrounded: 0 },
  citations: [
    { kind: 'TRANSACTION', marker: 1, txn: citedTxn },
    {
      kind: 'TOTAL',
      marker: 2,
      label: 'Groceries spending · September 2026',
      figures: [
        {
          field: 'spent',
          value: '477.94',
          relation: 'SUM',
          component: 'NET',
          terms: [{ sign: -1, drillDown: groceriesDrill }],
        },
      ],
      drillDowns: [groceriesDrill],
    },
    { kind: 'ALERT', marker: 3, alertKey: SAFEWAY.key, type: 'POSSIBLE_DUPLICATE', month: SAFEWAY.month, txns: [] },
    { kind: 'SERIES', marker: 4, accountId: 3, merchant: 'SPOTIFY USA', drillDown: spotifyDrill },
  ],
  dataNotes: [],
  usage: { model: 'test-main-model', rounds: 2, estimatedCost: '0.012345' },
  limitHit: null,
}

/** The Java-built list shown when the model's answer couldn't be checked. */
export const fallbackTurn: ChatTurn = {
  ...richTurn,
  turn: 2,
  answer: 'Here are the figures the tools returned:\n- Groceries spending · September 2026: $477.94 [1]',
  answerSource: 'FALLBACK',
  grounding: { status: 'FALLBACK', figuresChecked: 2, retried: true, ungrounded: 1 },
  citations: [{ ...richTurn.citations[1], marker: 1 }],
  usage: { model: 'test-main-model', rounds: 3, estimatedCost: null },
}

/** A turn a limit stopped. */
export const limitedTurn: ChatTurn = {
  ...fallbackTurn,
  turn: 3,
  limitHit: 'COST',
}

/** A gone transaction: its statement was deleted after the answer. */
export const goneTurn: ChatTurn = {
  ...richTurn,
  turn: 4,
  answer: 'That charge [1] is no longer in your statements.',
  citations: [
    {
      kind: 'TRANSACTION',
      marker: 1,
      txn: { ...citedTxn, status: 'GONE', id: null, accountId: null, accountName: null, txnDate: null, postDate: null, amount: null, merchant: null, category: null, statement: null, sourceLine: null },
    },
  ],
}

function stored(turn: ChatTurn, question: string): StoredTurn {
  return {
    turn: turn.turn,
    question,
    outboundQuestion: question,
    answer: turn.answer,
    answerSource: turn.answerSource,
    grounding: turn.grounding ?? null,
    citations: turn.citations,
    limitHit: turn.limitHit,
    usage: turn.usage,
    createdAt: '2026-10-23T12:00:00Z',
  }
}

export const conversation: Conversation = {
  id: 7,
  title: 'Groceries in September',
  createdAt: '2026-10-23T12:00:00Z',
  updatedAt: '2026-10-23T12:05:00Z',
  turns: [
    stored(richTurn, 'How much did I spend on groceries in September?'),
    {
      turn: 2,
      question: 'And in August?',
      outboundQuestion: 'And in August?',
      answer: null,
      answerSource: 'ERROR',
      grounding: null,
      citations: [],
      limitHit: null,
      usage: null,
      createdAt: '2026-10-23T12:05:00Z',
    },
  ],
}

export const conversations = {
  items: [
    { id: 7, title: 'Groceries in September', turns: 2, updatedAt: '2026-10-23T12:05:00Z' },
    { id: 3, title: null, turns: 1, updatedAt: '2026-10-20T08:00:00Z' },
  ],
  page: 0,
  size: 50,
  totalPages: 1,
}
