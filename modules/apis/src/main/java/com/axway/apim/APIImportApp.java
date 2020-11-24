package com.axway.apim;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axway.apim.adapter.APIManagerAdapter;
import com.axway.apim.adapter.apis.APIFilter;
import com.axway.apim.adapter.apis.APIFilter.Builder;
import com.axway.apim.api.API;
import com.axway.apim.apiimport.APIChangeState;
import com.axway.apim.apiimport.APIImportConfigAdapter;
import com.axway.apim.apiimport.APIImportManager;
import com.axway.apim.apiimport.lib.cli.CLIAPIImportOptions;
import com.axway.apim.apiimport.lib.params.APIImportParams;
import com.axway.apim.apiimport.rollback.RollbackHandler;
import com.axway.apim.cli.APIMCLIServiceProvider;
import com.axway.apim.cli.CLIServiceMethod;
import com.axway.apim.lib.APIPropertiesExport;
import com.axway.apim.lib.errorHandling.AppException;
import com.axway.apim.lib.errorHandling.ErrorCode;
import com.axway.apim.lib.errorHandling.ErrorCodeMapper;
import com.axway.apim.lib.errorHandling.ErrorState;
import com.axway.apim.lib.utils.rest.APIMHttpClient;

/**
 * This is the Entry-Point of program and responsible to:  
 * - read the command-line parameters to create a <code>CommandParameters</code>
 * - next is to read the API-Contract by creating an <code>APIImportConfig</code> instance and calling getImportAPIDefinition()
 * - the <code>APIManagerAdapter</code> method: <code>getAPIManagerAPI()</code> is used to create the API-Manager API state
 * - An <code>APIChangeState</code> is created based on ImportAPI and API-Manager API
 * - Finally the APIManagerAdapter:applyChanges() is called to replicate the state into the APIManager.   
 * 
 * @author cwiechmann@axway.com
 */
public class APIImportApp implements APIMCLIServiceProvider {

	private static Logger LOG = LoggerFactory.getLogger(APIImportApp.class);

	public static void main(String args[]) { 
		int rc = importAPI(args);
		System.exit(rc);
	}
	
	@CLIServiceMethod(name = "import", description = "Import APIs into the API-Manager")
	public static int importAPI(String args[]) {
		APIImportParams params;
		try {
			params = (APIImportParams)CLIAPIImportOptions.create(args).getParams();
		} catch (AppException e) {
			LOG.error("Error " + e.getMessage());
			return e.getErrorCode().getCode();
		}
		APIImportApp apiImportApp = new APIImportApp();
		return apiImportApp.importAPI(params);
	}

	public int importAPI(APIImportParams params) {
		ErrorCodeMapper errorCodeMapper = new ErrorCodeMapper();
		try {		
			params.validateRequiredParameters();
			// Clean some Singleton-Instances, as tests are running in the same JVM
			APIManagerAdapter.deleteInstance();
			ErrorState.deleteInstance();
			APIMHttpClient.deleteInstances();
			RollbackHandler.deleteInstance();

			errorCodeMapper.setMapConfiguration(params.getReturnCodeMapping());
			
			APIManagerAdapter apimAdapter = APIManagerAdapter.getInstance();
			
			APIImportConfigAdapter configAdapter = new APIImportConfigAdapter(params.getConfig(), 
					params.getStage(), params.getApiDefintion(), APIManagerAdapter.hasOrgAdmin());
			// Creates an API-Representation of the desired API
			API desiredAPI = configAdapter.getDesiredAPI();
			// 
			List<NameValuePair> filters = new ArrayList<NameValuePair>();
			// If we don't have an AdminAccount available, we ignore published APIs - For OrgAdmins 
			// the unpublished or pending APIs become the actual API
			if(!APIManagerAdapter.hasAdminAccount()) {
				filters.add(new BasicNameValuePair("field", "state"));
				filters.add(new BasicNameValuePair("op", "ne"));
				filters.add(new BasicNameValuePair("value", "published"));
			}
			// Lookup existing APIs - If found the actualAPI is valid - desiredAPI is used to control what needs to be loaded
			APIFilter filter = new APIFilter.Builder(Builder.APIType.ACTUAL_API)
					.hasApiPath(desiredAPI.getPath())
					.hasVHost(desiredAPI.getVhost())
					.includeCustomProperties(desiredAPI.getCustomProperties())
					.hasQueryStringVersion(desiredAPI.getApiRoutingKey())
					.includeClientOrganizations(true) // We have to load clientOrganization, in case they have to be taken over
					.includeQuotas(desiredAPI.getApplicationQuota()!=null) // and Quotas if not given in the Desired-API
					.includeClientApplications(true) // Client-Apps must be loaded in all cases
					.useFilter(filters)
					.useFEAPIDefinition(params.isUseFEAPIDefinition()) // Should API-Definition load from the FE-API?
					.build();
			API actualAPI = apimAdapter.apiAdapter.getAPI(filter, true);
			APIChangeState changes = new APIChangeState(actualAPI, desiredAPI);
			new APIImportManager().applyChanges(changes, params.isForceUpdate());
			APIPropertiesExport.getInstance().store();
			return 0;
		} catch (AppException ap) {
			APIPropertiesExport.getInstance().store(); // Try to create it, even 
			ErrorState errorState = ErrorState.getInstance();
			if(!ap.getErrorCode().equals(ErrorCode.NO_CHANGE)) {
				RollbackHandler rollback = RollbackHandler.getInstance();
				rollback.executeRollback();
			}
			if(errorState.hasError()) {
				errorState.logErrorMessages(LOG);
				if(errorState.isLogStackTrace()) LOG.error(ap.getMessage(), ap);
				return errorCodeMapper.getMapedErrorCode(errorState.getErrorCode()).getCode();
			} else {
				LOG.error(ap.getMessage(), ap);
				return errorCodeMapper.getMapedErrorCode(ap.getErrorCode()).getCode();
			}
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
			return ErrorCode.UNXPECTED_ERROR.getCode();
		}
	}

	@Override
	public String getGroupId() {
		return "api";
	}
	
	@Override
	public String getGroupDescription() {
		return "Manage your APIs";
	}

	@Override
	public String getVersion() {
		return APIImportApp.class.getPackage().getImplementationVersion();
	}
	
	public String getName() {
		return "API - I M P O R T";
	}
}
