import type { AxiosPromise } from '@/utils/api-types';
import request from '@/utils/request';
import type {
  ApplicationStatus,
  BidForm,
  InitiationForm,
  OpeningForm,
  PageData,
  PreparationForm,
  Project,
  ProjectApplication,
  ProjectDetail,
  ProjectForm,
  ProjectQuery
} from './types';

export const listProjects = (params: ProjectQuery): AxiosPromise<PageData<Project>> =>
  request({ url: '/project/projects', method: 'get', params });
export const getProject = (id: string): AxiosPromise<ProjectDetail> =>
  request({ url: `/project/projects/${id}`, method: 'get' });
export const createProject = (data: ProjectForm): AxiosPromise<ProjectDetail> =>
  request({ url: '/project/projects', method: 'post', data });
export const updateProject = (id: string, data: ProjectForm): AxiosPromise<ProjectDetail> =>
  request({ url: `/project/projects/${id}`, method: 'put', data });
const action = (id: string, path: string, data: unknown): AxiosPromise<ProjectDetail> =>
  request({ url: `/project/projects/${id}/${path}`, method: 'post', data });
export const submitRegistration = (id: string) => action(id, 'registration', {});
export const addFollowUp = (id: string, message: string) => action(id, 'follow-ups', { message });
export const terminateProject = (id: string, reason: string) => action(id, 'terminate', { reason });
export const submitBid = (id: string, data: BidForm) => action(id, 'bid', data);
export const addPreparation = (id: string, data: PreparationForm) => action(id, 'tasks', data);
export const updatePreparation = (
  id: string,
  taskId: string,
  data: { status: 'TODO' | 'DONE'; note?: string }
): AxiosPromise<ProjectDetail> => request({ url: `/project/projects/${id}/tasks/${taskId}`, method: 'put', data });
export const recordOpening = (id: string, data: OpeningForm) => action(id, 'opening', data);
export const submitInitiation = (id: string, data: InitiationForm) => action(id, 'initiation', data);
export const listApprovals = (params: {
  status: ApplicationStatus;
  pageNum: number;
  pageSize: number;
}): AxiosPromise<PageData<ProjectApplication>> => request({ url: '/project/approvals', method: 'get', params });
export const decideApproval = (id: string, decision: 'APPROVE' | 'RETURN', comment: string): AxiosPromise<null> =>
  request({ url: `/project/approvals/${id}/decision`, method: 'post', data: { decision, comment } });
