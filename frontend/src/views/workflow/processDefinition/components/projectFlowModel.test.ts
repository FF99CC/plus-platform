import { describe, expect, it } from 'vitest';
import {
  applyProjectChanges,
  parseCondition,
  serializeCondition,
  parseAssignees,
  serializeAssignees,
  submissionNodeCodes,
  canEditBranchCondition,
  simpleApprovalRatio
} from './projectFlowModel';

const definition = () => ({
  id: '90071992547409930',
  isPublish: 0,
  flowCode: 'pm_registration',
  listenerPath: 'existing.listener',
  ext: 'opaque',
  nodeList: [
    { nodeCode: 'start', nodeType: 0, skipList: [{ nextNodeCode: 'submit', skipType: 'PASS' }] },
    {
      nodeCode: 'submit',
      nodeType: 1,
      permissionFlag: '${initiator}',
      skipList: [{ nextNodeCode: 'review', skipType: 'PASS' }]
    },
    {
      nodeCode: 'review',
      nodeType: 1,
      permissionFlag: 'role:2',
      nodeRatio: 'spel@@#{custom}',
      listenerPath: 'keep',
      ext: '[{"code":"x","value":"y"}]',
      skipList: [
        {
          nowNodeCode: 'review',
          nextNodeCode: 'end',
          skipType: 'PASS',
          skipCondition: 'spel@@#{custom}',
          coordinate: '10,20;30,40'
        }
      ]
    },
    { nodeCode: 'end', nodeType: 2 }
  ]
});

describe('project flow edits preserve the stored graph', () => {
  it('offers branch conditions only on exclusive and inclusive gateways', () => {
    for (const type of [0, 1, 2, 4]) expect(canEditBranchCondition(type)).toBe(false);
    for (const type of [3, 5]) expect(canEditBranchCondition(type)).toBe(true);
  });
  it('recognizes decimal zero and full ratios without normalizing untouched data', () => {
    expect(simpleApprovalRatio('0.000')).toBe('0');
    expect(simpleApprovalRatio('100.000')).toBe('100');
    expect(simpleApprovalRatio('50.000')).toBeNull();
    expect(simpleApprovalRatio('spel@@#{custom}')).toBeNull();
    const source = definition();
    source.nodeList[2].nodeRatio = '0.000';
    expect(applyProjectChanges(source, [{ nodeCode: 'review', permissionFlag: '5' }], []).nodeList[2].nodeRatio).toBe(
      '0.000'
    );
  });
  it('round trips untouched custom data without rewriting anything', () => {
    const source = definition();
    expect(applyProjectChanges(source, [], [])).toEqual(source);
  });
  it('patches only requested fields and never changes the input', () => {
    const source = definition();
    const result = applyProjectChanges(
      source,
      [{ nodeCode: 'review', permissionFlag: '3@@role:2' }],
      [{ nodeCode: 'review', edgeIndex: 0, skipCondition: 'eq@@acquisitionMode|TENDER' }]
    );
    expect(source.nodeList[2].permissionFlag).toBe('role:2');
    expect(result.nodeList[2]).toEqual({
      ...source.nodeList[2],
      permissionFlag: '3@@role:2',
      skipList: [{ ...source.nodeList[2].skipList![0], skipCondition: 'eq@@acquisitionMode|TENDER' }]
    });
    expect(result.listenerPath).toBe(source.listenerPath);
  });
  it('refuses to edit the first submission task or a published definition', () => {
    expect(submissionNodeCodes(definition())).toEqual(new Set(['submit']));
    expect(() => applyProjectChanges(definition(), [{ nodeCode: 'submit', permissionFlag: '5' }], [])).toThrow();
    for (const isPublish of [1, 2])
      expect(() =>
        applyProjectChanges({ ...definition(), isPublish }, [{ nodeCode: 'review', permissionFlag: '5' }], [])
      ).toThrow();
  });
  it('does not accept synthetic fields or silently drop unknown assignees', () => {
    expect(parseCondition('spel@@#{custom}')).toBeNull();
    expect(parseCondition('eq@@applicationType|BID')).toBeNull();
    expect(parseCondition('eq@@acquisitionMode|OTHER')).toBeNull();
    expect(parseAssignees('1@@#{custom}')).toBeNull();
    expect(() => serializeCondition({ field: 'estimatedAmount', operator: 'ge', value: '' })).toThrow();
  });
  it('round trips supported conditions and assignee types', () => {
    for (const text of [
      'eq@@acquisitionMode|DIRECT',
      'ge@@estimatedAmount|1000000',
      'eq@@initiatorDeptId|100',
      'eq@@initiator|7',
      'eq@@projectType|咨询'
    ]) {
      expect(serializeCondition(parseCondition(text)!)).toBe(text);
    }
    const flags =
      '1@@role:2@@dept:3@@post:4@@${initiator}@@#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}';
    expect(serializeAssignees(parseAssignees(flags)!)).toBe(flags);
  });
});
