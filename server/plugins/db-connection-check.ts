import { prisma } from '../utils/prisma'

export default defineNitroPlugin(async (nitroApp) => {
  console.log('\n🔍 正在检查数据库连接...')
  
  try {
    await prisma.$queryRaw`SELECT 1 as result`
    console.log('✅ 数据库连接成功！')
    console.log('   数据库已就绪，可以正常使用\n')
  } catch (error: any) {
    console.error('\n❌ 数据库连接失败！')
    console.error('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━')
    
    if (error.code === 'ECONNREFUSED') {
      console.error('错误原因: 无法连接到数据库服务器')
      console.error('解决方案:')
      console.error('  1. 检查数据库服务是否已启动')
      console.error('  2. 检查 DATABASE_URL 中的主机地址和端口是否正确')
      console.error('  3. 检查防火墙设置是否阻止了连接')
    } else if (error.code === '3D000') {
      console.error('错误原因: 数据库不存在')
      console.error('解决方案:')
      console.error('  1. 创建数据库: CREATE DATABASE axis;')
      console.error('  2. 运行迁移: npx prisma migrate deploy')
    } else if (error.code === '28P01') {
      console.error('错误原因: 数据库认证失败（用户名或密码错误）')
      console.error('解决方案:')
      console.error('  1. 检查 .env 文件中的 DATABASE_URL')
      console.error('  2. 确认用户名和密码是否正确')
    } else if (error.code === 'ETIMEDOUT') {
      console.error('错误原因: 连接超时')
      console.error('解决方案:')
      console.error('  1. 检查网络连接')
      console.error('  2. 检查数据库服务器是否可达')
      console.error('  3. 增加 connectionTimeoutMillis 配置')
    } else {
      console.error('错误详情:', error.message)
      console.error('错误代码:', error.code || '未知')
    }
    
    console.error('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━')
    console.error('DATABASE_URL:', process.env.DATABASE_URL?.replace(/:[^:@]+@/, ':****@'))
    console.error('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n')
    
    if (process.env.NODE_ENV === 'production') {
      process.exit(1)
    } else {
      console.warn('⚠️  开发环境：应用将继续运行，但数据库功能可能无法使用\n')
    }
  }
})
