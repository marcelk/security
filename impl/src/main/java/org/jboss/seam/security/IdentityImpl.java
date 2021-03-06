package org.jboss.seam.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Named;

import org.jboss.seam.security.events.AlreadyLoggedInEvent;
import org.jboss.seam.security.events.LoggedInEvent;
import org.jboss.seam.security.events.LoginFailedEvent;
import org.jboss.seam.security.events.NotAuthorizedEvent;
import org.jboss.seam.security.events.NotLoggedInEvent;
import org.jboss.seam.security.events.PostAuthenticateEvent;
import org.jboss.seam.security.events.PostLoggedOutEvent;
import org.jboss.seam.security.events.PreAuthenticateEvent;
import org.jboss.seam.security.events.PreLoggedOutEvent;
import org.jboss.seam.security.events.QuietLoginEvent;
import org.jboss.seam.security.permission.PermissionMapper;
import org.picketlink.idm.api.Credential;
import org.picketlink.idm.api.Group;
import org.picketlink.idm.api.IdentitySession;
import org.picketlink.idm.api.Role;
import org.picketlink.idm.api.RoleType;
import org.picketlink.idm.api.User;
import org.picketlink.idm.common.exception.FeatureNotSupportedException;
import org.picketlink.idm.common.exception.IdentityException;
import org.picketlink.idm.impl.api.model.SimpleGroup;
import org.picketlink.idm.impl.api.model.SimpleRole;
import org.picketlink.idm.impl.api.model.SimpleRoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity implementation for authorization and authentication via Seam security.
 * 
 * @author Shane Bryzak
 */
public @Named("identity") @SessionScoped class IdentityImpl implements Identity, Serializable
{
   private static final long serialVersionUID = 3751659008033189259L;
   
   private static final String RESPONSE_LOGIN_SUCCESS = "success";
   private static final String RESPONSE_LOGIN_FAILED = "failed";
   private static final String RESPONSE_LOGIN_EXCEPTION = "exception";
   
   protected static boolean securityEnabled = true;
   
   Logger log = LoggerFactory.getLogger(IdentityImpl.class);
  
   @Inject BeanManager beanManager;
   
   @Inject private Credentials credentials;
   @Inject private PermissionMapper permissionMapper;
   
   @Inject private IdentitySession identitySession;
   
   @Inject Instance<RequestSecurityState> requestSecurityState;
   @Inject Instance<Authenticator> authenticators;
   
   private User user;

   /**
    * Contains a group name to group type:role list mapping of roles assigned 
    * during the authentication process
    */
   private Map<String,Map<String,List<String>>> preAuthenticationRoles = new HashMap<String,Map<String,List<String>>>();

   private Set<Role> activeRoles = new HashSet<Role>();
   
   /**
    * Map of group name:group type group memberships assigned during the 
    * authentication process
    */
   private Map<String,List<String>> preAuthenticationGroups = new HashMap<String,List<String>>();
   
   private Set<Group> activeGroups = new HashSet<Group>();
   
   private transient ThreadLocal<Boolean> systemOp;
   
   /**
    * Flag that indicates we are in the process of authenticating
    */
   private boolean authenticating = false;
   
   public static boolean isSecurityEnabled()
   {
      return securityEnabled;
   }
   
   public static void setSecurityEnabled(boolean enabled)
   {
      securityEnabled = enabled;
   }
   
   public boolean isLoggedIn()
   {
      // If there is a user set, then the user is logged in.
      return user != null;
   }
   
   public boolean tryLogin()
   {      
      if (!authenticating && getUser() == null && credentials.isSet() && 
            !requestSecurityState.get().isLoginTried())
      {
         requestSecurityState.get().setLoginTried(true);
         quietLogin();
      }
      
      return isLoggedIn();
   }
   
   /**
    * Performs an authorization check, based on the specified security expression.
    * 
    * @param expr The security expression to evaluate
    * @throws NotLoggedInException Thrown if the authorization check fails and
    * the user is not authenticated
    * @throws AuthorizationException Thrown if the authorization check fails and
    * the user is authenticated
    */
   // QUESTION should we add the dependency on el-api for the sake of avoiding reinstantiating the VE?
   
   // TODO redesign restrictions system to be typesafe
   /*
   public void checkRestriction(ValueExpression expression)
   {
      if (!securityEnabled)
      {
         return;
      }
      
      if (!expressions.getValue(expression, Boolean.class))
      {
         if (!isLoggedIn())
         {
            manager.fireEvent(new NotLoggedInEvent());
            
            log.debug(String.format(
               "Error evaluating expression [%s] - User not logged in", expression.getExpressionString()));
            throw new NotLoggedInException();
         }
         else
         {
            manager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
               "Authorization check failed for expression [%s]", expression.getExpressionString()));
         }
      }
   }*/
   
   /**
    * Performs an authorization check, based on the specified security expression string.
    * 
    * @param expr The security expression string to evaluate
    * @throws NotLoggedInException Thrown if the authorization check fails and
    * the user is not authenticated
    * @throws AuthorizationException Thrown if the authorization check fails and
    * the user is authenticated
    */
   
   /*
   public void checkRestriction(String expr)
   {
      if (!securityEnabled)
      {
         return;
      }
      
      checkRestriction(expressions.createValueExpression(expr, Boolean.class).toUnifiedValueExpression());
   }*/

   public String login()
   {
      try
      {
         if (isLoggedIn())
         {
            // If authentication has already occurred during this request via a silent login,
            // and login() is explicitly called then we still want to raise the LOGIN_SUCCESSFUL event,
            // and then return.
            if (requestSecurityState.get().isSilentLogin())
            {
               beanManager.fireEvent(new LoggedInEvent(user));
               return RESPONSE_LOGIN_SUCCESS;
            }
            
            beanManager.fireEvent(new AlreadyLoggedInEvent());
            return RESPONSE_LOGIN_SUCCESS;
         }
         
         boolean success = authenticate();
                  
         if (success)
         {
            if (log.isDebugEnabled())
            {
               log.debug("Login successful for: " + credentials);
            }
            beanManager.fireEvent(new LoggedInEvent(user));
            return RESPONSE_LOGIN_SUCCESS;
         }
         
         credentials.invalidate();         
         return RESPONSE_LOGIN_FAILED;
      }
      catch (Exception ex)
      {
         log.error("Login failed for: " + credentials, ex);
         
         beanManager.fireEvent(new LoginFailedEvent(ex));
         
         return RESPONSE_LOGIN_EXCEPTION;
      }
   }
   
   public void quietLogin() 
   {
      try
      {
         beanManager.fireEvent(new QuietLoginEvent());
          
         // Ensure that we haven't been authenticated as a result of the EVENT_QUIET_LOGIN event
         if (!isLoggedIn())
         {
            if (credentials.isSet())
            {
               authenticate();
               
               if (isLoggedIn())
               {
                  requestSecurityState.get().setSilentLogin(true);
               }
            }
         }
      }
      catch (Exception ex)
      {
         log.error("Error authenticating", ex);
         credentials.invalidate();
      }
   }
    
   protected boolean authenticate() throws IdentityException, FeatureNotSupportedException
   {
      try
      {
         authenticating = true;
         
         user = null;
         
         preAuthenticate();
         
         Authenticator authenticator;
         
         if (authenticators.isAmbiguous())
         {
            throw new IllegalStateException("More than one Authenticator bean found - please ensure " +
               "only one Authenticator implementation is provided");
         }
         else if (authenticators.isUnsatisfied())
         {
            authenticator = null;
         }
         else
         {
            authenticator = authenticators.get();
         }
                     
         boolean success = false;
         
         if (authenticator != null)
         {
            success = authenticator.authenticate();
         }
         else
         {
            // Otherwise if identity management is enabled, use it.
            if (identitySession != null)
            {            
               User u = new UserImpl(credentials.getUsername()); 
               
               success = identitySession.getAttributesManager().validateCredentials(
                     u, new Credential[] {credentials.getCredential()});
               
               if (success)
               {
                  Collection<RoleType> roleTypes = identitySession.getRoleManager()
                      .findUserRoleTypes(u);
                  
                  for (RoleType roleType : roleTypes)
                  {
                     for (Role role : identitySession.getRoleManager().findRoles(u, roleType))
                     {
                        addRole(role.getRoleType().getName(), 
                              role.getGroup().getName(), role.getGroup().getGroupType());   
                     }
                  }                  
               }
            }
         }
         
         if (success)
         {
            user = new UserImpl(credentials.getUsername());
            postAuthenticate();
         }
         
         return success;
      }
      finally
      {
         // Set credential to null whether authentication is successful or not
         credentials.setCredential(null);
         authenticating = false;
      }
   }
   
   /**
    * Clears any roles added by calling addRole() while not authenticated.
    * This method may be overridden by a subclass if different
    * pre-authentication logic should occur.
    */
   protected void preAuthenticate()
   {
      preAuthenticationRoles.clear();
      beanManager.fireEvent(new PreAuthenticateEvent());
   }
   
   /**
    * Extracts the principal from the subject, and uses it to create the User object.  
    * This method may be overridden by a subclass if
    * different post-authentication logic should occur.
    */
   protected void postAuthenticate()
   {  
      if (isLoggedIn())
      {
         if (!preAuthenticationRoles.isEmpty())
         {
            for (String group : preAuthenticationRoles.keySet())
            {
               Map<String,List<String>> groupTypeRoles = preAuthenticationRoles.get(group);
               for (String groupType : groupTypeRoles.keySet())
               {
                  for (String roleType : groupTypeRoles.get(groupType))
                  {
                     addRole(roleType, group, groupType);
                  }
               }
            }
            preAuthenticationRoles.clear();
         }
         
         if (!preAuthenticationGroups.isEmpty())
         {
            for (String group : preAuthenticationGroups.keySet())
            {
               for (String groupType : preAuthenticationGroups.get(group))
               {
                  activeGroups.add(new SimpleGroup(group, groupType));  
               }               
            }
            preAuthenticationGroups.clear();
         }         
      }
      
      beanManager.fireEvent(new PostAuthenticateEvent());
   }
   
   /**
    * Resets all security state and credentials
    */
   public void unAuthenticate()
   {
      user = null;      
      credentials.clear();
   }
   
   public void logout()
   {
      if (isLoggedIn())
      {
         PostLoggedOutEvent loggedOutEvent = new PostLoggedOutEvent(user);
         
         beanManager.fireEvent(new PreLoggedOutEvent());
         unAuthenticate();
         
         // TODO - invalidate the session
         // Session.instance().invalidate();
         
         beanManager.fireEvent(loggedOutEvent);
      }
   }

   public boolean hasRole(String roleType, String group, String groupType)
   {
      if (!securityEnabled) return true;
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return true;
      
      tryLogin();
      
      for (Role role : activeRoles)
      {
         if (role.getRoleType().getName().equals(roleType) && 
               role.getGroup().getName().equals(group) &&
               role.getGroup().getGroupType().equals(groupType))
         {
            return true;
         }
      }
      
      return false;
   }
   
   public boolean addRole(String roleType, String group, String groupType)
   {
      if (roleType == null || "".equals(roleType) || group == null || "".equals(group) 
            || groupType == null || "".equals(groupType)) return false;
      
      if (isLoggedIn())
      {
         return activeRoles.add(new SimpleRole(new SimpleRoleType(roleType), 
               user, new SimpleGroup(group, groupType)));
      }
      else
      {
         List<String> roleTypes = null;
         
         Map<String,List<String>> groupTypes = preAuthenticationRoles.get(group);
         if (groupTypes != null)
         {
            roleTypes = groupTypes.get(groupType);
         }
         else
         {
            groupTypes = new HashMap<String,List<String>>();
            preAuthenticationRoles.put(group, groupTypes);
         }
         
         if (roleTypes == null)
         {
            roleTypes = new ArrayList<String>();
            groupTypes.put(groupType, roleTypes);         
         }
         
         return roleTypes.add(roleType);         
      }      
   }
   
   public boolean inGroup(String name, String groupType)
   {
      for (Group group : activeGroups)
      {
         if (group.getName().equals(name) && group.getGroupType().equals(groupType)) return true;
      }
      
      return false;
   }
   
   public boolean addGroup(String name, String groupType)
   {
      if (name == null || "".equals(name) || groupType == null || "".equals(groupType))
      {
         return false;
      }
      
      if (isLoggedIn())
      {
         return activeGroups.add(new SimpleGroup(name, groupType));
      }
      else
      {         
         List<String> groupTypes = null;
         if (preAuthenticationGroups.containsKey(name))
         {
            groupTypes = preAuthenticationGroups.get(name);
         }
         else
         {
            groupTypes = new ArrayList<String>();
            preAuthenticationGroups.put(name, groupTypes);
         }
         
         return groupTypes.add(groupType);         
      }
   }
   
   public void removeGroup(String name, String groupType)
   {
      for (Group group : activeGroups)
      {
         if (group.getName().equals(name) && group.getGroupType().equals(groupType))
         {
            activeGroups.remove(group);
            return;
         }
      }
   }

   /**
    * Removes a role from the authenticated user
    * 
    * @param role The name of the role to remove
    */
   public void removeRole(String roleType, String group, String groupType)
   {   
      for (Role role : activeRoles)
      {
         if (role.getRoleType().getName().equals(roleType) && 
               role.getGroup().getName().equals(group) &&
               role.getGroup().getGroupType().equals(groupType))
         {
            activeRoles.remove(role);
            return;
         }
      }
   }
   
   public void checkRole(String roleType, String group, String groupType)
   {
      tryLogin();
      
      if ( !hasRole(roleType, group, groupType) )
      {
         if ( !isLoggedIn() )
         {
            beanManager.fireEvent(new NotLoggedInEvent());
            throw new NotLoggedInException();
         }
         else
         {
            beanManager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
                  "Authorization check failed for role [%s:%s:%s]", roleType, group, groupType));
         }
      }
   }
   
   public void checkGroup(String group, String groupType)
   {
      tryLogin();
      
      if ( !inGroup(group, groupType) )
      {
         if ( !isLoggedIn() )
         {
            beanManager.fireEvent(new NotLoggedInEvent());
            throw new NotLoggedInException();
         }
         else
         {
            beanManager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
                  "Authorization check failed for group [%s:%s]", group, groupType));
         }
      }
   }   
   
   public void checkPermission(Object target, String action)
   {
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return;
      
      tryLogin();
      
      if ( !hasPermission(target, action) )
      {
         if ( !isLoggedIn() )
         {
            beanManager.fireEvent(new NotLoggedInEvent());
            throw new NotLoggedInException();
         }
         else
         {
            beanManager.fireEvent(new NotAuthorizedEvent());
            throw new AuthorizationException(String.format(
                  "Authorization check failed for permission[%s,%s]", target, action));
         }
      }
   }
   
   public void filterByPermission(Collection<?> collection, String action)
   {
      permissionMapper.filterByPermission(collection, action);
   }
   
   public boolean hasPermission(Object target, String action)
   {
      if (!securityEnabled) return true;
      if (systemOp != null && Boolean.TRUE.equals(systemOp.get())) return true;
      if (permissionMapper == null) return false;
      if (target == null) return false;
      
      return permissionMapper.resolvePermission(target, action);
   }
   
   public synchronized void runAs(RunAsOperation operation)
   {
      User savedUser = getUser();
      
      if (systemOp == null)
      {
         systemOp = new ThreadLocal<Boolean>();
      }
      
      boolean savedSystemOp = systemOp.get();
      
      try
      {
         user = operation.getUser();         
         
         systemOp.set(operation.isSystemOperation());
         
         operation.execute();
      }
      finally
      {
         systemOp.set(savedSystemOp);
         user = savedUser;
      }
   }

   public void checkRestriction(String expr)
   {
      // TODO Do we still need this method?
      
   }

   public User getUser()
   {
      return user;
   }

   public Set<Role> getRoles()
   {
      return Collections.unmodifiableSet(activeRoles);
   }

   public Set<Group> getGroups()
   {
      return Collections.unmodifiableSet(activeGroups);
   }

   public boolean isVerified()
   {
      // TODO Auto-generated method stub
      return false;
   }
}
