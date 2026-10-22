import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';

export interface DesignEdge {
  nowNodeCode?: string;
  nextNodeCode: string;
  skipName?: string;
  skipType?: string;
  skipCondition?: string;
  [key: string]: unknown;
}
export interface DesignNode {
  nodeCode: string;
  nodeName?: string;
  nodeType: number;
  permissionFlag?: string;
  nodeRatio?: string;
  skipList?: DesignEdge[];
  [key: string]: unknown;
}
export interface DesignDefinition {
  id: string;
  flowCode: string;
  isPublish?: number;
  nodeList: DesignNode[];
  [key: string]: unknown;
}
export interface HandlerOption {
  storageId: string;
  handlerName?: string;
  groupName?: string;
}
export interface HandlerResult {
  handlerAuths: { rows: HandlerOption[]; total: number };
  treeSelections?: { id: string; label: string; children?: unknown[] }[];
}
export interface RoutePreview {
  complete: boolean;
  steps: {
    nodeCode: string;
    nodeName: string;
    nodeType: number;
    assignees: { userId: string; userName: string }[];
    configuredAssignees?: string[];
    description?: string;
  }[];
  warnings: string[];
  matchedEdges?: { fromNodeCode: string; toNodeCode: string; condition?: string }[];
}
export const queryDesign = async (id: string): AxiosPromise<DesignDefinition> => {
  const response: Awaited<AxiosPromise<DesignDefinition>> = await request({
    url: `/warm-flow/query-def/${id}`,
    method: 'get'
  });
  // WarmFlow's exported DefJson omits the definition ID; the designer restores it from its URL.
  // Use the same route identity for both initial loading and the pre-save freshness check.
  if (response.data) response.data = { ...response.data, id };
  return response;
};
export const saveDesign = (data: DesignDefinition) =>
  request({ url: '/warm-flow/save-json', method: 'post', headers: { onlyNodeSkip: true }, data });
export const queryHandlers = (params: Record<string, string | number>): AxiosPromise<HandlerResult> =>
  request({ url: '/warm-flow/handler-result', method: 'get', params });
export const handlerNames = (storageIds: string[]): AxiosPromise<HandlerOption[]> =>
  request({ url: '/warm-flow/handler-feedback', method: 'get', params: { storageIds } });
export const previewProjectRoute = (data: {
  definitionId: string;
  initiatorId: string;
  variables: Record<string, string | number>;
}): AxiosPromise<RoutePreview> => request({ url: '/project/workflow/preview', method: 'post', data });
