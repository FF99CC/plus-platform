import { describe, expect, it, vi } from 'vitest';
import type { DesignDefinition } from './designer';
import { applyProjectChanges } from '../../../views/workflow/processDefinition/components/projectFlowModel';
import { queryDesign, saveDesign } from './designer';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/utils/request', () => ({ default: request }));

describe('existing flow definition identity', () => {
  it('saves edits under the route ID when WarmFlow returns a null JSON ID', async () => {
    const routeId = '90071992547409930';
    request.mockResolvedValueOnce({
      code: 200,
      data: {
        id: null,
        flowCode: 'pm_registration',
        isPublish: 0,
        nodeList: [{ nodeCode: 'review', nodeType: 1, permissionFlag: 'role:2' }]
      }
    });
    const response = await queryDesign(routeId);
    expect(response.data?.id).toBe(routeId);
    const edited = applyProjectChanges(
      response.data as DesignDefinition,
      [{ nodeCode: 'review', permissionFlag: '7' }],
      []
    );
    request.mockResolvedValueOnce({ code: 200 });
    await saveDesign(edited);
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/warm-flow/save-json',
        data: expect.objectContaining({ id: routeId, flowCode: 'pm_registration' })
      })
    );
  });
});
