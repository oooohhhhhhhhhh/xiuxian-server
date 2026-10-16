import * as api from '../api.js';
import * as utils from '../utils.js';

export async function load() {
  try {
    const data = await api.get('/users');
    
    let allRoles = [], allPerms = [];
    try {
      const [roles, perms] = await Promise.all([api.get('/roles'), api.get('/permissions')]);
      allRoles = roles.roles.map(r => r.name);
      allPerms = perms.permissions || [];
    } catch {}
    
    let html = '';
    
    if (api.hasPerm('admin.users.manage')) {
      const adminRoles = allRoles.filter(r => ['SUPER_ADMIN', 'ADMIN', 'MODERATOR'].includes(r));
      html += `
        <div class="card" style="margin-bottom:16px">
          <div class="card-title">➕ 创建后台管理员</div>
          <div class="row" style="flex-wrap:wrap;gap:8px;align-items:flex-end">
            <input id="newAdminUsername" placeholder="用户名" style="width:140px">
            <input id="newAdminPassword" type="password" placeholder="密码(至少6位)" style="width:140px">
            <select id="newAdminRole" style="width:120px">
              ${adminRoles.map(r => `<option value="${r}"${r === 'ADMIN' ? ' selected' : ''}>${r}</option>`).join('')}
            </select>
            <button class="btn btn-primary" onclick="window.app.modules.users.createAdmin()">创建管理员</button>
          </div>
          <div id="createAdminMsg" style="margin-top:8px;color:var(--crimson);font-size:12px"></div>
        </div>
      `;
    }
    
    if (!data.users || data.users.length === 0) {
      html += utils.createEmptyState('👤', '暂无用户');
    } else {
      html += '<table><tr><th>ID</th><th>用户名</th><th>角色</th><th>单独权限</th><th>操作</th></tr>';
      
      for (const user of data.users) {
        const roleTags = (user.roles || []).map(role => 
          `<span class="tag">${utils.escapeHtml(role)}${api.hasPerm('admin.users.manage') ? ` <span class="tag-x" onclick="window.app.modules.users.removeRole(${user.id}, '${utils.escapeHtml(role)}')" title="移除角色">&times;</span>` : ''}</span>`
        ).join(' ');
        
        const directPerms = user.directPermissions || [];
        const permTags = directPerms.map(perm => 
          `<span class="tag tag-perm">${utils.escapeHtml(perm)}${api.hasPerm('admin.users.manage') ? ` <span class="tag-x" onclick="window.app.modules.users.removePermission(${user.id}, '${utils.escapeHtml(perm)}')" title="移除权限">&times;</span>` : ''}</span>`
        ).join(' ');
        
        const availablePerms = allPerms.filter(p => !directPerms.includes(p.code));
        const permOptions = availablePerms.map(p => 
          `<option value="${p.code}">${utils.escapeHtml(p.code)} (${utils.escapeHtml(p.category)})</option>`
        ).join('');
        
        const permRow = api.hasPerm('admin.users.manage') && allPerms.length > 0 ? `
          <div style="margin-top:4px;display:flex;gap:4px;align-items:center">
            <select id="permSel_${user.id}" style="width:180px;font-size:11px"><option value="">--添加权限--</option>${permOptions}</select>
            <button class="btn btn-action btn-sm" onclick="window.app.modules.users.assignPermission(${user.id})">+</button>
          </div>
        ` : '';
        
        const roleSelect = api.hasPerm('admin.users.manage') && allRoles.length > 0 ? `
          <select id="roleSel_${user.id}" style="width:100px">
            ${allRoles.map(r => `<option value="${r}">${r}</option>`).join('')}
          </select>
          <button class="btn btn-primary btn-sm" onclick="window.app.modules.users.assignRole(${user.id})">分配</button>
        ` : '';
        
        const actions = api.hasPerm('admin.users.manage') ? `
          ${roleSelect}
          <button class="btn btn-danger btn-sm" onclick="window.app.modules.users.deleteUser(${user.id})" style="margin-left:4px">删除</button>
          <button class="btn btn-action btn-sm" onclick="window.app.modules.users.resetPassword(${user.id})" style="margin-left:4px">重置密码</button>
        ` : '';
        
        html += `
          <tr>
            <td style="color:var(--text-muted)">${user.id}</td>
            <td>${utils.escapeHtml(user.username)}</td>
            <td><span class="tags">${roleTags || '<span class="badge badge-gray">无</span>'}</span></td>
            <td><span class="tags">${permTags || '<span class="badge badge-gray">—</span>'}</span>${permRow}</td>
            <td>${actions}</td>
          </tr>
        `;
      }
      
      html += '</table>';
    }
    
    document.getElementById('userRolesTable').innerHTML = html;
  } catch (error) {
    document.getElementById('userRolesTable').innerHTML = utils.createEmptyState('👤', '加载失败: ' + (error.message || ''));
  }
}

export async function createAdmin() {
  const username = document.getElementById('newAdminUsername').value.trim();
  const password = document.getElementById('newAdminPassword').value;
  const role = document.getElementById('newAdminRole').value;
  const msgEl = document.getElementById('createAdminMsg');
  
  if (!username) {
    msgEl.textContent = '请输入用户名';
    return;
  }
  if (password.length < 6) {
    msgEl.textContent = '密码至少6位';
    return;
  }
  
  try {
    const result = await api.post('/user/create-admin', { username, password, role });
    if (result.code === 200) {
      msgEl.textContent = '';
      document.getElementById('newAdminUsername').value = '';
      document.getElementById('newAdminPassword').value = '';
      load();
    } else {
      msgEl.textContent = result.message || '创建失败';
    }
  } catch (error) {
    msgEl.textContent = '创建失败: ' + (error.message || '网络错误');
  }
}

export async function deleteUser(uid) {
  if (!confirm('确定删除用户 #' + uid + ' 吗？此操作不可恢复！')) return;
  
  try {
    await api.del('/user/' + uid);
    load();
  } catch {
    alert('删除失败');
  }
}

export async function resetPassword(uid) {
  const newPassword = prompt('请输入新密码（至少6位）:');
  if (!newPassword || newPassword.length < 6) {
    alert('密码至少6位');
    return;
  }
  
  try {
    const result = await api.post('/user/' + uid + '/password', { password: newPassword });
    if (result.code === 200) {
      alert('密码修改成功');
    } else {
      alert(result.message || '修改失败');
    }
  } catch {
    alert('修改失败');
  }
}

export async function assignPermission(uid) {
  const sel = document.getElementById('permSel_' + uid);
  const perm = sel.value;
  if (!perm) {
    alert('请选择权限');
    return;
  }
  
  try {
    await api.post('/user/' + uid + '/permissions', { permission: perm });
    load();
  } catch {
    alert('权限分配失败');
  }
}

export async function removePermission(uid, permCode) {
  if (!confirm('确定移除用户 #' + uid + ' 的权限【' + permCode + '】吗？')) return;
  
  try {
    await api.del('/user/' + uid + '/permissions/' + encodeURIComponent(permCode));
    load();
  } catch {
    alert('权限移除失败');
  }
}

export async function assignRole(uid) {
  const role = document.getElementById('roleSel_' + uid).value;
  
  try {
    await api.post('/user/' + uid + '/role', { role });
    load();
  } catch {
    alert('分配失败');
  }
}

export async function removeRole(uid, roleName) {
  if (!confirm('确定移除用户 #' + uid + ' 的角色【' + roleName + '】吗？')) return;
  
  try {
    await api.del('/user/' + uid + '/role/' + encodeURIComponent(roleName));
    load();
  } catch {
    alert('移除失败');
  }
}