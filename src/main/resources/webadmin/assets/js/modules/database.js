import * as api from '../api.js';
import * as utils from '../utils.js';

let currentTable = null;
let columns = null;
let page = 0;
let pageSize = 50;

export async function loadTables() {
  try {
    const data = await api.get('/db/tables?t=' + Date.now());
    const list = document.getElementById('dbTableList');
    
    if (!data.tables || data.tables.length === 0) {
      list.innerHTML = utils.createEmptyState('📋', '无数据表');
      return;
    }
    
    let html = '';
    for (const table of data.tables) {
      html += `
        <div class="db-table-item${currentTable === table.name ? ' active' : ''}" onclick="window.app.modules.database.selectTable('${utils.escapeHtml(table.name)}')">
          <span>${utils.escapeHtml(table.name)}</span>
          <span class="row-count">${table.rowCount >= 0 ? table.rowCount : '?'}</span>
        </div>
      `;
    }
    list.innerHTML = html;
    
    if (currentTable) {
      loadTableData();
    }
  } catch {
    document.getElementById('dbTableList').innerHTML = utils.createEmptyState('📋', '加载失败');
  }
}

export function selectTable(name) {
  currentTable = name;
  page = 0;
  document.getElementById('dbTableTitle').textContent = '📄 ' + name;
  document.getElementById('btnDbAdd').style.display = 'inline-block';
  
  document.querySelectorAll('.db-table-item').forEach(item => {
    item.classList.toggle('active', item.querySelector('span').textContent === name);
  });
  
  loadTableData();
}

export async function loadTableData() {
  if (!currentTable) return;
  
  try {
    const data = await api.get(`/db/tables/${currentTable}?limit=${pageSize}&offset=${page * pageSize}&t=${Date.now()}`);
    columns = data.columns || [];
    const rows = data.rows || [];
    const total = data.total || 0;
    
    let html = '<table><tr>';
    for (const col of columns) {
      html += `<th>${utils.escapeHtml(col.name)}${col.pk ? ' 🔑' : ''}</th>`;
    }
    html += '<th>操作</th></tr>';
    
    for (const row of rows) {
      html += '<tr>';
      for (const col of columns) {
        const val = row[col.name];
        const display = val === null ? '<span style="color:var(--text-muted)">NULL</span>' : utils.escapeHtml(String(val));
        html += `<td>${display}</td>`;
      }
      html += `
        <td>
          <button class="btn btn-action btn-sm" onclick="window.app.modules.database.openEdit(${row.id})">编辑</button>
          <button class="btn btn-danger btn-sm" onclick="window.app.modules.database.deleteRow(${row.id})">删除</button>
        </td>
      `;
      html += '</tr>';
    }
    html += '</table>';
    
    if (rows.length === 0) {
      html += utils.createEmptyState('📋', '该表暂无数据');
    }
    
    document.getElementById('dbDataTable').innerHTML = html;

    // 统一分页组件 + 每页条数选择
    utils.renderPagination('dbPagination', total, page, pageSize,
      p => `window.app.modules.database.goToPage(${p})`);
    const pagEl = document.getElementById('dbPagination');
    pagEl.insertAdjacentHTML('beforeend',
      ` <span style="font-size:12px;color:var(--text-muted)">每页</span>` +
      `<select style="width:60px" onchange="window.app.modules.database.changePageSize(parseInt(this.value))">` +
      `<option value="20"${pageSize === 20 ? ' selected' : ''}>20</option>` +
      `<option value="50"${pageSize === 50 ? ' selected' : ''}>50</option>` +
      `<option value="100"${pageSize === 100 ? ' selected' : ''}>100</option>` +
      `</select> 行`);
  } catch (error) {
    utils.showEmpty('dbDataTable', '📋', '加载失败: ' + (error.message || ''));
    document.getElementById('dbPagination').innerHTML = '';
  }
}

export function goToPage(newPage) {
  page = newPage;
  loadTableData();
}

export function changePageSize(newSize) {
  page = 0;
  pageSize = newSize;
  loadTableData();
}

export function refresh() {
  if (currentTable) {
    loadTableData();
  } else {
    loadTables();
  }
}

export function openAddRow() {
  if (!columns) return;
  
  let fieldsHtml = '';
  for (const col of columns) {
    if (col.pk) continue;
    fieldsHtml += `
      <label>${utils.escapeHtml(col.name)}${col.notnull ? ' *' : ''}</label>
      <input id="dbFld_${utils.escapeHtml(col.name)}" placeholder="${utils.escapeHtml(col.type || '')}">
    `;
  }
  
  const html = `
    <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
    <h3>➕ 新增行 · ${utils.escapeHtml(currentTable || '')}</h3>
    <div class="db-edit-grid">${fieldsHtml}</div>
    <div class="actions">
      <button class="btn btn-primary" onclick="window.app.modules.database.saveNewRow()">保存</button>
    </div>
  `;
  
  utils.showModal(html);
}

export async function saveNewRow() {
  const data = {};
  for (const col of columns) {
    if (col.pk) continue;
    const el = document.getElementById('dbFld_' + col.name);
    if (!el) continue;
    const val = el.value.trim();
    if (val === '' && col.notnull) {
      alert('字段 ' + col.name + ' 不能为空');
      return;
    }
    if (val !== '') {
      const type = (col.type || '').toLowerCase();
      if (type.includes('int') || type.includes('bigint') || type.includes('tinyint') || 
          type.includes('smallint') || type.includes('decimal') || type.includes('float') || 
          type.includes('double') || type.includes('real') || type.includes('numeric')) {
        data[col.name] = parseFloat(val);
      } else if (type.includes('bool')) {
        data[col.name] = val === 'true' || val === '1';
      } else {
        data[col.name] = val;
      }
    }
  }
  
  if (Object.keys(data).length === 0) {
    alert('请至少填写一个字段');
    return;
  }
  
  try {
    const result = await api.post('/db/tables/' + currentTable, data);
    if (result.code === 200) {
      utils.showMessage('dbMsg', 'ok', '新增成功 (ID: ' + result.id + ')');
      utils.closeModal();
      loadTableData();
      loadTables();
    } else {
      alert('新增失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('新增失败: ' + (error.message || ''));
  }
}

export async function openEdit(id) {
  try {
    const data = await api.get('/db/tables/' + currentTable + '/' + id);
    const row = data.row;
    
    if (!row) {
      alert('记录不存在');
      return;
    }
    
    let fieldsHtml = '';
    for (const col of columns) {
      const val = row[col.name];
      const strVal = val === null ? '' : String(val);
      const disabled = col.pk ? ' disabled style="opacity:0.5"' : '';
      fieldsHtml += `
        <label>${utils.escapeHtml(col.name)}${col.pk ? ' 🔑' : ''}${col.notnull ? ' *' : ''}</label>
        <input id="dbFld_${utils.escapeHtml(col.name)}" value="${utils.escapeHtml(strVal)}"${disabled}>
      `;
    }
    
    const html = `
      <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
      <h3>✏️ 编辑行 · ${utils.escapeHtml(currentTable)} · ID: ${id}</h3>
      <div class="db-edit-grid">${fieldsHtml}</div>
      <div class="actions">
        <button class="btn btn-primary" onclick="window.app.modules.database.saveEdit(${id})">保存修改</button>
      </div>
    `;
    
    utils.showModal(html);
  } catch (error) {
    alert('加载失败: ' + (error.message || ''));
  }
}

export async function saveEdit(id) {
  const data = {};
  for (const col of columns) {
    if (col.pk) continue;
    const el = document.getElementById('dbFld_' + col.name);
    if (!el) continue;
    const val = el.value.trim();
    if (val === '' && col.notnull) {
      alert('字段 ' + col.name + ' 不能为空');
      return;
    }
    if (val !== '') {
      const type = (col.type || '').toLowerCase();
      if (type.includes('int') || type.includes('bigint') || type.includes('tinyint') || 
          type.includes('smallint') || type.includes('decimal') || type.includes('float') || 
          type.includes('double') || type.includes('real') || type.includes('numeric')) {
        data[col.name] = parseFloat(val);
      } else if (type.includes('bool')) {
        data[col.name] = val === 'true' || val === '1';
      } else {
        data[col.name] = val;
      }
    } else {
      data[col.name] = null;
    }
  }
  
  if (Object.keys(data).length === 0) {
    alert('没有需要修改的字段');
    return;
  }
  
  try {
    const result = await api.post('/db/tables/' + currentTable + '/' + id, data);
    if (result.code === 200) {
      utils.showMessage('dbMsg', 'ok', '更新成功');
      utils.closeModal();
      loadTableData();
    } else {
      alert('更新失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('更新失败: ' + (error.message || ''));
  }
}

export async function deleteRow(id) {
  if (!confirm('确定删除 ' + currentTable + ' 表中 ID=' + id + ' 的记录吗？此操作不可撤销！')) return;
  
  try {
    const result = await api.del('/db/tables/' + currentTable + '/' + id);
    if (result.code === 200) {
      utils.showMessage('dbMsg', 'ok', '删除成功');
      loadTableData();
      loadTables();
    } else {
      alert('删除失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('删除失败: ' + (error.message || ''));
  }
}

export async function downloadBackup() {
  try {
    utils.showMessage('dbMsg', 'ok', '正在导出数据，请稍候...');
    const response = await api.download('/backup');
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const disposition = response.headers.get('Content-Disposition') || '';
    const filenameMatch = disposition.match(/filename="?(.+?)"?$/);
    a.download = filenameMatch ? filenameMatch[1] : 'xiuxian_backup.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    utils.showMessage('dbMsg', 'ok', '✅ 备份文件下载成功');
  } catch (error) {
    utils.showMessage('dbMsg', 'err', '备份失败: ' + (error.message || ''));
  }
}

export async function importBackup(fileInput) {
  const file = fileInput.files[0];
  if (!file) return;
  
  if (!confirm('⚠️ 导入数据将覆盖当前所有数据，此操作不可撤销！\n\n确定要导入文件「' + file.name + '」吗？')) {
    fileInput.value = '';
    return;
  }
  
  try {
    utils.showMessage('dbMsg', 'ok', '正在导入数据，请稍候...');
    const text = await file.text();
    const payload = JSON.parse(text);
    const result = await api.post('/backup/import', payload);
    if (result.code === 200) {
      let detail = '';
      if (result.detail) {
        for (const [key, val] of Object.entries(result.detail)) {
          if (key.endsWith('_deleted') || key.endsWith('_errors') || key.endsWith('_delete_error')) continue;
          detail += key + ': ' + val + ' 条; ';
        }
      }
      utils.showMessage('dbMsg', 'ok', '✅ ' + result.message + '（共 ' + (result.totalImported || 0) + ' 条）<br><small style="color:var(--text-muted)">' + detail + '</small>');
      loadTables();
    } else {
      utils.showMessage('dbMsg', 'err', '导入失败: ' + (result.message || ''));
    }
  } catch (error) {
    utils.showMessage('dbMsg', 'err', '导入失败: ' + (error.message || ''));
  }
  
  fileInput.value = '';
}

export async function executeAction(action) {
  try {
    const result = await api.post('/database/' + action);
    let detail = '';
    if (result.detail) {
      for (const [key, val] of Object.entries(result.detail)) {
        detail += key + ': ' + val + ' 条; ';
      }
    }
    utils.showMessage('dbMsg', 'ok', result.message + '<br><small style="color:var(--text-muted)">' + detail + '</small>');
    loadTables();
  } catch {
    utils.showMessage('dbMsg', 'err', '操作失败');
  }
}